package moe.cnkirito.kiritodb;

import com.alibabacloud.polar_race.engine.common.Util;
import com.carrotsearch.hppc.LongIntHashMap;
import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicLong;

import static moe.cnkirito.kiritodb.Constant.INDEX_SIZE;

/**
 * @author kirito.moe@foxmail.com
 * Date 2018-10-28
 */
public class MemoryIndex {

    Logger logger = LoggerFactory.getLogger(MemoryIndex.class);

    private RandomAccessFile randomAccessFile;
    private LongIntHashMap[] indexMapArray;
    private FileChannel indexFileChannel;
    private AtomicLong wrotePosition;

    public MemoryIndex(String path) {
        this.indexMapArray = new LongIntHashMap[Constant.INDEX_MAP_NUM];
        for (int i = 0; i < Constant.INDEX_MAP_NUM; i++) {
            indexMapArray[i] = new LongIntHashMap();
        }
        File file = new File(path);
        if (!file.exists()) {
            logger.info("create index file.");
            try {
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }else{
            logger.info("index file already exsit.");
        }
        long endPosition = 0L;
        try {
            RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
            this.randomAccessFile = randomAccessFile;
            this.indexFileChannel = randomAccessFile.getChannel();
            endPosition = randomAccessFile.length();
            logger.info("current index file size: {}", randomAccessFile.length());
            wrotePosition = new AtomicLong(randomAccessFile.length());
        } catch (IOException e) {
            logger.error("file not found", e);
        }
        ByteBuffer byteBuffer = ByteBuffer.allocate(INDEX_SIZE);
        for (long i = 0; i < endPosition; i += Constant.INDEX_SIZE) {
            byteBuffer.clear();
            try {
                int size = this.indexFileChannel.read(byteBuffer);
                if (size == -1) {
                    break;
                }
                byteBuffer.flip();
                long key = byteBuffer.getLong();
                int offset = byteBuffer.getInt();
                // 插入内存
                LongIntHashMap indexes = indexMapArray[(int) (Math.abs(key) % Constant.INDEX_MAP_NUM)];
                synchronized (indexes) {
                    indexes.put(key, offset);
                }
            } catch (IOException e) {
                logger.error("io exception", e);
            }
        }
        logger.info("load index from file to memory, num: {}", endPosition / Constant.INDEX_SIZE);
    }

    public void recordPosition(byte[] key, long position) {
        // 先存文件
        int offsetInt = (int) (position / Constant.DATA_SIZE) + 1;
        ByteBuffer buffer = ByteBuffer.allocate(Constant.INDEX_SIZE);
        buffer.clear();
        buffer.put(key);
        buffer.putInt(offsetInt);
        buffer.flip();
        try {
            long offset = wrotePosition.getAndAdd(Constant.INDEX_SIZE);
            while (buffer.hasRemaining()) {
                indexFileChannel.write(buffer, offset + (Constant.INDEX_SIZE - buffer.remaining()));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        // 后放内存
        long keyL = Util.bytes2Long(key);
        LongIntHashMap indexes = indexMapArray[(int) (Math.abs(keyL) % Constant.INDEX_MAP_NUM)];
        synchronized (indexes) {
            indexes.put(keyL, offsetInt);
        }
    }

    public Long getPosition(byte[] key) {
        long keyL = Util.bytes2Long(key);
        LongIntHashMap indexes = indexMapArray[(int) (Math.abs(keyL) % Constant.INDEX_MAP_NUM)];
        int offsetInt = indexes.get(keyL);
        if (offsetInt == 0)
            return null;
        else
            return ((long) offsetInt - 1) * Constant.DATA_SIZE;
    }

    public void close() {
        if (this.indexFileChannel != null) {
            try {
                this.indexFileChannel.force(true);
            } catch (IOException e) {
                logger.error("force error", e);
            }
        }
        if (randomAccessFile != null) {
            try {
                randomAccessFile.close();
            } catch (IOException e) {
                logger.error("randomAccessFile close error", e);
            }
        }
        logger.info("memoryIndex closed.");
    }

}