/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.elasticsearch.compute.aggregation.GroupingAggregatorFunction;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.LongBlock;

/**
 * Removes duplicate values from multivalued positions.
 */
public class MultivalueDedupeBoolean {
    /**
     * Ordinal assigned to {@code null}.
     */
    public static final int NULL_ORD = 0;
    /**
     * Ordinal assigned to {@code false}.
     */
    public static final int FALSE_ORD = 1;
    /**
     * Ordinal assigned to {@code true}.
     */
    public static final int TRUE_ORD = 2;

    private final Block.Ref ref;
    private final BooleanBlock block;
    private boolean seenTrue;
    private boolean seenFalse;

    public MultivalueDedupeBoolean(Block.Ref ref) {
        this.ref = ref;
        this.block = (BooleanBlock) ref.block();
    }

    /**
     * Dedupe values using an adaptive algorithm based on the size of the input list.
     */
    public Block.Ref dedupeToBlock(BlockFactory blockFactory) {
        if (false == block.mayHaveMultivaluedFields()) {
            return ref;
        }
        try (ref) {
            BooleanBlock.Builder builder = BooleanBlock.newBlockBuilder(block.getPositionCount(), blockFactory);
            for (int p = 0; p < block.getPositionCount(); p++) {
                int count = block.getValueCount(p);
                int first = block.getFirstValueIndex(p);
                switch (count) {
                    case 0 -> builder.appendNull();
                    case 1 -> builder.appendBoolean(block.getBoolean(first));
                    default -> {
                        readValues(first, count);
                        writeValues(builder);
                    }
                }
            }
            return Block.Ref.floating(builder.build());
        }
    }

    /**
     * Dedupe values and build a {@link LongBlock} suitable for passing
     * as the grouping block to a {@link GroupingAggregatorFunction}.
     * @param everSeen array tracking if the values {@code false} and {@code true} are ever seen
     */
    public IntBlock hash(boolean[] everSeen) {
        IntBlock.Builder builder = IntBlock.newBlockBuilder(block.getPositionCount());
        for (int p = 0; p < block.getPositionCount(); p++) {
            int count = block.getValueCount(p);
            int first = block.getFirstValueIndex(p);
            switch (count) {
                case 0 -> {
                    everSeen[NULL_ORD] = true;
                    builder.appendInt(NULL_ORD);
                }
                case 1 -> builder.appendInt(hashOrd(everSeen, block.getBoolean(first)));
                default -> {
                    readValues(first, count);
                    hashValues(everSeen, builder);
                }
            }
        }
        return builder.build();
    }

    /**
     * Build a {@link BatchEncoder} which deduplicates values at each position
     * and then encodes the results into a {@link byte[]} which can be used for
     * things like hashing many fields together.
     */
    public BatchEncoder batchEncoder(int batchSize) {
        return new BatchEncoder.Booleans(Math.max(2, batchSize)) {
            @Override
            protected void readNextBatch() {
                for (int position = firstPosition(); position < block.getPositionCount(); position++) {
                    if (hasCapacity(2) == false) {
                        return;
                    }
                    int count = block.getValueCount(position);
                    int first = block.getFirstValueIndex(position);
                    switch (count) {
                        case 0 -> encodeNull();
                        case 1 -> {
                            boolean v = block.getBoolean(first);
                            startPosition();
                            encode(v);
                            endPosition();
                        }
                        default -> {
                            readValues(first, count);
                            startPosition();
                            encodeUniquedWork(this);
                            endPosition();
                        }
                    }
                }
            }
        };
    }

    private void readValues(int first, int count) {
        int end = first + count;

        seenFalse = false;
        seenTrue = false;
        for (int i = first; i < end; i++) {
            if (block.getBoolean(i)) {
                seenTrue = true;
                if (seenFalse) {
                    break;
                }
            } else {
                seenFalse = true;
                if (seenTrue) {
                    break;
                }
            }
        }
    }

    private void writeValues(BooleanBlock.Builder builder) {
        if (seenFalse) {
            if (seenTrue) {
                builder.beginPositionEntry();
                builder.appendBoolean(false);
                builder.appendBoolean(true);
                builder.endPositionEntry();
            } else {
                builder.appendBoolean(false);
            }
        } else if (seenTrue) {
            builder.appendBoolean(true);
        } else {
            throw new IllegalStateException("didn't see true of false but counted values");
        }
    }

    private void hashValues(boolean[] everSeen, IntBlock.Builder builder) {
        if (seenFalse) {
            if (seenTrue) {
                builder.beginPositionEntry();
                builder.appendInt(hashOrd(everSeen, false));
                builder.appendInt(hashOrd(everSeen, true));
                builder.endPositionEntry();
            } else {
                builder.appendInt(hashOrd(everSeen, false));
            }
        } else if (seenTrue) {
            builder.appendInt(hashOrd(everSeen, true));
        } else {
            throw new IllegalStateException("didn't see true of false but counted values");
        }
    }

    private void encodeUniquedWork(BatchEncoder.Booleans encoder) {
        if (seenFalse) {
            encoder.encode(false);
        }
        if (seenTrue) {
            encoder.encode(true);
        }
    }

    /**
     * Convert the boolean to an ordinal and track if it's been seen in {@code everSeen}.
     */
    public static int hashOrd(boolean[] everSeen, boolean b) {
        if (b) {
            everSeen[TRUE_ORD] = true;
            return TRUE_ORD;
        }
        everSeen[FALSE_ORD] = true;
        return FALSE_ORD;
    }
}
