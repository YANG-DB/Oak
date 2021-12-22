/*
 * Copyright 2020, Verizon Media.
 * Licensed under the terms of the Apache 2.0 license.
 * Please see LICENSE file in the project root for terms.
 */

package com.yahoo.oak;


import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

abstract class InternalOakBasics<K, V> {
    /*-------------- Members --------------*/
    protected static final int MAX_RETRIES = 1024;

    private final MemoryManager valuesMemoryManager;
    private final MemoryManager keysMemoryManager;

    private final OakSerializer<K> keySerializer;
    private final OakSerializer<V> valueSerializer;

    private final ValueUtils valueOperator;

    protected final AtomicInteger size;

    /*-------------- Constructors --------------*/
    InternalOakBasics(MemoryManager vMM, MemoryManager kMM,
                      OakSerializer<K> keySerializer, OakSerializer<V> valueSerializer, ValueUtils valueOperator) {
        this.size = new AtomicInteger(0);
        this.valuesMemoryManager = vMM;
        this.keysMemoryManager = kMM;
        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
        this.valueOperator = valueOperator;
    }

    /*-------------- Closable --------------*/
    /**
     * cleans only off heap memory
     */
    void close() {
        try {
            // closing the same memory manager (or memory allocator) twice,
            // has the same effect as closing once
            valuesMemoryManager.close();
            keysMemoryManager.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /*-------------- size --------------*/
    /**
     * @return current off heap memory usage in bytes
     */
    long memorySize() {
        if (valuesMemoryManager != keysMemoryManager) {
            // Two memory managers are not the same instance, but they
            // may still have the same allocator and allocator defines how many bytes are allocated
            if (valuesMemoryManager.getBlockMemoryAllocator()
                != keysMemoryManager.getBlockMemoryAllocator()) {
                return valuesMemoryManager.allocated() + keysMemoryManager.allocated();
            }
        }
        return valuesMemoryManager.allocated();
    }

    int entries() {
        return size.get();
    }

    /* getter methods */
    public MemoryManager getValuesMemoryManager() {
        return this.valuesMemoryManager;
    }

    public MemoryManager getKeysMemoryManager() {
        return keysMemoryManager;
    }

    protected OakSerializer<K> getKeySerializer() {
        return this.keySerializer;
    }

    protected OakSerializer<V> getValueSerializer() {
        return this.valueSerializer;
    }

    protected ValueUtils getValueOperator() {
        return valueOperator;
    }
    /*-------------- Context --------------*/
    /**
     * Should only be called from API methods at the beginning of the method and be reused in internal calls.
     *
     * @return a context instance.
     */
    ThreadContext getThreadContext() {
        return new ThreadContext(keysMemoryManager, valuesMemoryManager);
    }

    /*-------------- REBALANCE --------------*/
    /**
    * Tunneling for a specific chunk rebalance to be implemented in concrete internal map or hash
    * */
    protected abstract void rebalanceBasic(BasicChunk<K, V> c);

    protected void checkRebalance(BasicChunk<K, V> c) {
        if (c.shouldRebalance()) {
            rebalanceBasic(c);
        }
    }

    protected void helpRebalanceIfInProgress(BasicChunk<K, V> c) {
        if (c.state() == BasicChunk.State.FROZEN) {
            rebalanceBasic(c);
        }
    }

    protected boolean inTheMiddleOfRebalance(BasicChunk<K, V> c) {
        BasicChunk.State state = c.state();
        if (state == BasicChunk.State.INFANT) {
            // the infant is already connected so rebalancer won't add this put
            rebalanceBasic(c.creator());
            return true;
        }
        if (state == BasicChunk.State.FROZEN || state == BasicChunk.State.RELEASED) {
            rebalanceBasic(c);
            return true;
        }
        return false;
    }

    /*-------------- Common actions --------------*/

    Result remove(K key, V oldValue, OakTransformer<V> transformer) {
        if (key == null) {
            throw new NullPointerException();
        }

        // when logicallyDeleted is true, it means we have marked the value as deleted.
        // Note that the entry will remain linked until rebalance happens.
        boolean logicallyDeleted = false;
        V v = null;

        ThreadContext ctx = getThreadContext();

        for (int i = 0; i < MAX_RETRIES; i++) {
            // find chunk matching key, puts this key hash into ctx.operationKeyHash
            BasicChunk<K, V> c = findChunk(key, ctx);
            c.lookUp(ctx, key);

            if (!ctx.isKeyValid()) {
                // There is no such key. If we did logical deletion and someone else did the physical deletion,
                // then the old value is saved in v. Otherwise v is (correctly) null
                return transformer == null ? ctx.result.withFlag(logicallyDeleted) : ctx.result.withValue(v);
            } else if (!ctx.isValueValid()) {
                // There is such a key, but the value is invalid,
                // either deleted (maybe only off-heap) or not yet allocated
                if (!finalizeDeletion(c, ctx)) {
                    // finalize deletion returns false, meaning no rebalance was requested
                    // and there was an attempt to finalize deletion
                    return transformer == null ? ctx.result.withFlag(logicallyDeleted) : ctx.result.withValue(v);
                }
                continue;
            }

            // AT THIS POINT Key was found (key and value not valid) and context is updated
            if (logicallyDeleted) {
                // This is the case where we logically deleted this entry (marked the value off-heap as deleted),
                // but someone helped and (marked the value reference as deleted) and reused the entry
                // before we marked the value reference as deleted. We have the previous value saved in v.
                return transformer == null ? ctx.result.withFlag(ValueUtils.ValueResult.TRUE) : ctx.result.withValue(v);
            } else {
                Result removeResult = valueOperator.remove(ctx, oldValue, transformer);
                if (removeResult.operationResult == ValueUtils.ValueResult.FALSE) {
                    // we didn't succeed to remove the value: it didn't contain oldValue, or was already marked
                    // as deleted by someone else)
                    return ctx.result.withFlag(ValueUtils.ValueResult.FALSE);
                } else if (removeResult.operationResult == ValueUtils.ValueResult.RETRY) {
                    continue;
                }
                // we have marked this value as deleted (successful remove)
                logicallyDeleted = true;
                v = (V) removeResult.value;
            }

            // AT THIS POINT value was marked deleted off-heap by this thread,
            // continue to set the entry's value reference as deleted
            assert ctx.entryIndex != EntryArray.INVALID_ENTRY_INDEX;
            assert ctx.isValueValid();
            ctx.entryState = EntryArray.EntryState.DELETED_NOT_FINALIZED;

            if (inTheMiddleOfRebalance(c)) {
                continue;
            }

            // If finalize deletion returns true, meaning rebalance was done and there was NO
            // attempt to finalize deletion. There is going the help anyway, by next rebalance
            // or updater. Thus it is OK not to restart, the linearization point of logical deletion
            // is owned by this thread anyway and old value is kept in v.
            finalizeDeletion(c, ctx); // includes publish/unpublish
            return transformer == null ?
                    ctx.result.withFlag(ValueUtils.ValueResult.TRUE) : ctx.result.withValue(v);
        }

        throw new RuntimeException("remove failed: reached retry limit (1024).");
    }

    // the zero-copy version of get
    abstract OakUnscopedBuffer get(K key);

    protected abstract BasicChunk<K, V> findChunk(K key, ThreadContext ctx);
    protected boolean finalizeDeletion(BasicChunk<K, V> c, ThreadContext ctx) {
        if (c.finalizeDeletion(ctx)) {
            rebalanceBasic(c);
            return true;
        }
        return false;
    }

    protected boolean isAfterRebalanceOrValueUpdate(BasicChunk<K, V> c, ThreadContext ctx) {
        // If orderedChunk is frozen or infant, can't proceed with put, need to help rebalancer first,
        // rebalance is done as part of inTheMiddleOfRebalance.
        // Also if value is off-heap deleted, we need to finalizeDeletion on-heap, which can
        // cause rebalance as well. If rebalance happened finalizeDeletion returns true.
        // After rebalance we need to restart.
        if (inTheMiddleOfRebalance(c) || finalizeDeletion(c, ctx)) {
            return true;
        }

        // Value can be valid again, if key was found and partially deleted value needed help.
        // But in the meanwhile value was reset to be another, valid value.
        // In Hash case value will be always invalid in the context, but the changes will be caught
        // during next entry allocation
        if (ctx.isValueValid()) {
            return true;
        }

        return false;
    }

    /**
     * See {@code refreshValuePosition(ctx)} for more details.
     *
     * @param key   the key to refresh
     * @param value the output value to update
     * @return true if the refresh was successful.
     */
    boolean refreshValuePosition(KeyBuffer key, ValueBuffer value) {
        ThreadContext ctx = getThreadContext();
        ctx.key.copyFrom(key);
        boolean isSuccessful = refreshValuePosition(ctx);

        if (!isSuccessful) {
            return false;
        }

        value.copyFrom(ctx.value);
        return true;
    }

    /**
     * Used when value of a key was possibly moved and we try to search for the given key
     * through the OakMap again.
     *
     * @param ctx The context key should be initialized with the key to refresh, and the context value
     *            will be updated with the refreshed value.
     * @reutrn true if the refresh was successful.
     */
    abstract boolean refreshValuePosition(ThreadContext ctx);


    protected <T> T getValueTransformation(OakScopedReadBuffer key, OakTransformer<T> transformer) {
        K deserializedKey = keySerializer.deserialize(key);
        return getValueTransformation(deserializedKey, transformer);
    }

    // the non-ZC variation of the get
    abstract <T> T getValueTransformation(K key, OakTransformer<T> transformer);

    /*-------------- Different Oak Buffer creations --------------*/

    protected UnscopedBuffer getKeyUnscopedBuffer(ThreadContext ctx) {
        return new UnscopedBuffer<>(new KeyBuffer(ctx.key));
    }

    protected UnscopedValueBufferSynced getValueUnscopedBuffer(ThreadContext ctx) {
        return new UnscopedValueBufferSynced(ctx.key, ctx.value, this);
    }

    // Iterator State base class
    static class BasicIteratorState<K, V> {

        private BasicChunk<K, V> chunk;
        private BasicChunk.BasicChunkIter chunkIter;
        private int index;

        public void set(BasicChunk<K, V> chunk, BasicChunk.BasicChunkIter chunkIter, int index) {
            this.chunk = chunk;
            this.chunkIter = chunkIter;
            this.index = index;
        }

        protected BasicIteratorState(BasicChunk<K, V> nextChunk, BasicChunk.BasicChunkIter nextChunkIter,
                                     int nextIndex) {

            this.chunk = nextChunk;
            this.chunkIter = nextChunkIter;
            this.index = nextIndex;
        }

        BasicChunk<K, V> getChunk() {
            return chunk;
        }

        BasicChunk.BasicChunkIter getChunkIter() {
            return chunkIter;
        }

        public int getIndex() {
            return index;
        }

        public void copyState(BasicIteratorState<K, V> other) {
            assert other != null;
            this.chunk = other.chunk;
            this.chunkIter = other.chunkIter;
            this.index = other.index;
        }
    }


    /************************
    * Basic Iterator class
    *************************/
    abstract class BasicIter<T> implements Iterator<T> {


        /**
         * the next node to return from next();
         */
        private BasicIteratorState<K, V> state;
        private BasicIteratorState<K, V> prevIterState;
        private boolean prevIterStateValid = false;
        /**
         * An iterator cannot be accesses concurrently by multiple threads.
         * Thus, it is safe to have its own thread context.
         */
        protected ThreadContext ctx;

        /**
         * Initializes ascending iterator for entire range.
         */
        BasicIter() {
            this.ctx = new ThreadContext(keysMemoryManager, valuesMemoryManager);
        }

        public boolean hasNext() {
            return (state != null);
        }

        protected abstract void initAfterRebalance();


        // the actual next()
        public abstract T next();

        /**
         * The function removes the element returned by the last call to next() function
         * If the next() was not called, exception is thrown
         * If the entry was changed, between the call of the next() and remove(), it is deleted regardless
         */
        @Override
        public void remove() {
            if (!isPrevIterStateValid()) {
                throw new IllegalStateException("next() was not called in due order");
            }
            BasicChunk prevChunk = getPrevIterState().getChunk();
            int preIdx = getPrevIterState().getIndex();
            boolean validState = prevChunk.readKeyFromEntryIndex(ctx.key, preIdx);
            if (validState) {
                K prevKey = getKeySerializer().deserialize(ctx.key);
                InternalOakBasics.this.remove(prevKey, null, null);
            }

            invalidatePrevState();
        }
        /**
         * Advances next to higher entry.
         *  previous index
         *
         * The first long is the key's reference, the integer is the value's version and the second long is
         * the value's reference. If {@code needsValue == false}, then the value of the map entry is {@code null}.
         */
        void advance(boolean needsValue) {
            boolean validState = false;

            while (!validState) {
                if (state == null) {
                    throw new NoSuchElementException();
                }

                final BasicChunk<K, V> chunk = state.getChunk();
                if (chunk.state() == BasicChunk.State.RELEASED) {
                    // @TODO not to access the keys on the RELEASED chunk once the key might be released
                    initAfterRebalance();
                    continue;
                }

                final int curIndex = state.getIndex();

                // build the entry context that sets key references and does not check for value validity.
                ctx.initEntryContext(curIndex);


                chunk.readKey(ctx);

                validState = ctx.isKeyValid();

                if (validState & needsValue) {
                    // Set value references and checks for value validity.
                    // if value is deleted ctx.entryState is going to be invalid
                    chunk.readValue(ctx);
                    validState = ctx.isValueValid();
                }

                advanceState();
            }
        }

        /**
         * Advances next to the next entry without creating a ByteBuffer for the key.
         * Return previous index
         */
        abstract void advanceStream(UnscopedBuffer<KeyBuffer> key, UnscopedBuffer<ValueBuffer> value);





        protected BasicIteratorState<K, V> getState() {
            return state;
        }
        protected void setState(BasicIteratorState<K, V> newState) {
            state = newState;
        }
        protected void setPrevState(BasicIteratorState<K, V> newState) {
            prevIterState = newState;
        }

        protected BasicIteratorState<K, V> getPrevIterState() {
            return prevIterState;
        }
        /**
         * function copies the fields of the current iterator state to the fields of the pre.IterState
         * This is used by the remove function of the iterator
         */
        protected void storeIterState() {
            prevIterState.copyState(state);
            prevIterStateValid = true;
        }

        protected boolean isPrevIterStateValid() {
            return prevIterStateValid;
        }
        protected void invalidatePrevState() {
            prevIterStateValid = false;
        }

        protected abstract BasicChunk<K, V> getNextChunk(BasicChunk<K, V> current);
        protected abstract BasicChunk.BasicChunkIter getChunkIter(BasicChunk<K, V> current);

        /**
         * advance state to the new position
         * @return if new position found, return true, else, set State to null and return false
         */
        protected boolean advanceState() {

            BasicChunk<K, V> chunk = getState().getChunk();
            BasicChunk.BasicChunkIter chunkIter = getState().getChunkIter();

            while (!chunkIter.hasNext()) { // skip empty chunks
                chunk = getNextChunk(chunk);
                if (chunk == null) {
                    //End of iteration
                    setState(null);
                    return false;
                }
                chunkIter = getChunkIter(chunk);
            }

            int nextIndex = chunkIter.next(ctx);
            storeIterState();
            getState().set(chunk, chunkIter, nextIndex);

            return true;
        }

    }
}



