/*
 * Copyright (c) 2013. Jordan Williams
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jordanwilliams.heftydb.write;

import com.jordanwilliams.heftydb.io.DataFile;
import com.jordanwilliams.heftydb.io.MutableDataFile;
import com.jordanwilliams.heftydb.state.Paths;
import com.jordanwilliams.heftydb.table.file.Index;
import com.jordanwilliams.heftydb.table.file.IndexBlock;
import com.jordanwilliams.heftydb.table.file.IndexRecord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class IndexWriter {

    private final DataFile indexFile;
    private final int maxIndexBlockSize;
    private final List<IndexBlock.Builder> indexBlockBuilders = new ArrayList<IndexBlock.Builder>();
    private final List<Index.LeafBlock> leafBlocks = new ArrayList<Index.LeafBlock>();

    private IndexWriter(long tableId, Paths paths, int maxIndexBlockSize) throws IOException {
        this.indexFile = MutableDataFile.open(paths.indexPath(tableId));
        this.maxIndexBlockSize = maxIndexBlockSize;
        indexBlockBuilders.add(new IndexBlock.Builder());
    }

    public void write(IndexRecord indexRecord) throws IOException {
        Queue<IndexRecord> pendingIndexRecord = new LinkedList<IndexRecord>();
        pendingIndexRecord.add(indexRecord);

        for (int i = 0; i < indexBlockBuilders.size(); i++) {
            if (pendingIndexRecord.isEmpty()) {
                return;
            }

            IndexBlock.Builder levelBuilder = indexBlockBuilders.get(i);

            if (levelBuilder.size() >= maxIndexBlockSize) {
                IndexRecord metaRecord = writeIndexBlock(levelBuilder.build());

                IndexBlock.Builder newLevelBuilder = new IndexBlock.Builder();
                newLevelBuilder.addRecord(pendingIndexRecord.poll());
                indexBlockBuilders.set(i, newLevelBuilder);

                pendingIndexRecord.add(metaRecord);
            } else {
                levelBuilder.addRecord(pendingIndexRecord.poll());
            }
        }

        if (!pendingIndexRecord.isEmpty()) {
            IndexBlock.Builder newLevelBuilder = new IndexBlock.Builder();
            newLevelBuilder.addRecord(pendingIndexRecord.poll());
            indexBlockBuilders.add(newLevelBuilder);
        }
    }

    public void finish() throws IOException {
        Queue<IndexRecord> pendingIndexRecord = new LinkedList<IndexRecord>();

        for (int i = 0; i < indexBlockBuilders.size(); i++) {
            IndexBlock.Builder levelBuilder = indexBlockBuilders.get(i);

            if (!pendingIndexRecord.isEmpty()) {
                levelBuilder.addRecord(pendingIndexRecord.poll());
            }

            IndexRecord nextLevelRecord = writeIndexBlock(levelBuilder.build());
            pendingIndexRecord.add(nextLevelRecord);
        }

        writeLeafBlocks();

        IndexRecord rootIndexRecord = pendingIndexRecord.poll();
        indexFile.appendInt(rootIndexRecord.blockSize());
        indexFile.appendLong(rootIndexRecord.blockOffset());
        indexFile.close();
    }

    private void writeLeafBlocks() throws IOException {
        for (Index.LeafBlock leafBlock : leafBlocks){
            indexFile.appendLong(leafBlock.offset());
            indexFile.appendInt(leafBlock.size());
        }

        indexFile.appendInt(leafBlocks.size());
    }

    private IndexRecord writeIndexBlock(IndexBlock indexBlock) throws IOException {
        ByteBuffer indexBlockBuffer = indexBlock.memory().directBuffer();
        indexBlockBuffer.rewind();
        long indexBlockOffset = indexFile.append(indexBlockBuffer);

        if (indexBlock.startRecord().isLeaf()){
            leafBlocks.add(new Index.LeafBlock(indexBlockOffset, indexBlockBuffer.capacity()));
        }

        IndexRecord metaIndexRecord = new IndexRecord(indexBlock.startRecord().startKey(), indexBlockOffset, indexBlockBuffer.capacity(), false);
        indexBlock.memory().release();
        return metaIndexRecord;
    }

    public static IndexWriter open(long tableId, Paths paths, int maxIndexSize) throws IOException {
        return new IndexWriter(tableId, paths, maxIndexSize);
    }
}
