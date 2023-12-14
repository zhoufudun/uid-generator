/*
 * Copyright (c) 2017 Baidu, Inc. All Rights Reserve.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.baidu.fsg.uid.impl;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.util.Assert;

import com.baidu.fsg.uid.BitsAllocator;
import com.baidu.fsg.uid.UidGenerator;
import com.baidu.fsg.uid.buffer.BufferPaddingExecutor;
import com.baidu.fsg.uid.buffer.RejectedPutBufferHandler;
import com.baidu.fsg.uid.buffer.RejectedTakeBufferHandler;
import com.baidu.fsg.uid.buffer.RingBuffer;
import com.baidu.fsg.uid.exception.UidGenerateException;

/**
 * Represents a cached implementation of {@link UidGenerator} extends
 * from {@link DefaultUidGenerator}, based on a lock free {@link RingBuffer}<p>
 * 
 * The spring properties you can specified as below:<br>
 * <li><b>boostPower:</b> RingBuffer size boost for a power of 2, Sample: boostPower is 3, it means the buffer size 
 *                        will be <code>({@link BitsAllocator#getMaxSequence()} + 1) &lt;&lt;
 *                        {@link #boostPower}</code>, Default as {@value #DEFAULT_BOOST_POWER}
 * <li><b>paddingFactor:</b> Represents a percent value of (0 - 100). When the count of rest available UIDs reach the 
 *                           threshold, it will trigger padding buffer. Default as{@link RingBuffer#DEFAULT_PADDING_PERCENT}
 *                           Sample: paddingFactor=20, bufferSize=1000 -> threshold=1000 * 20 /100, padding buffer will be triggered when tail-cursor<threshold
 * <li><b>scheduleInterval:</b> Padding buffer in a schedule, specify padding buffer interval, Unit as second
 * <li><b>rejectedPutBufferHandler:</b> Policy for rejected put buffer. Default as discard put request, just do logging
 * <li><b>rejectedTakeBufferHandler:</b> Policy for rejected take buffer. Default as throwing up an exception
 *
 * 该类是一种模式之一：RingBuffer，它可以缓存未来几秒的uid，提高并发
 * 另外一种模式为：DefaultUidGenerator，他只能在一秒内获取，一秒内用完了，只能等待，并发不如RingBuffer
 * @author yutianbao
 */
public class CachedUidGenerator extends DefaultUidGenerator implements DisposableBean {
    private static final Logger LOGGER = LoggerFactory.getLogger(CachedUidGenerator.class);
    private static final int DEFAULT_BOOST_POWER = 3;

    /** Spring properties */
    private int boostPower = DEFAULT_BOOST_POWER;
    private int paddingFactor = RingBuffer.DEFAULT_PADDING_PERCENT;
    private Long scheduleInterval; // 50s
    
    private RejectedPutBufferHandler rejectedPutBufferHandler; // ringBuffer满了的时候放不进去的拒绝策略
    private RejectedTakeBufferHandler rejectedTakeBufferHandler; // ringBuffer空了的时候获取不到的拒绝策略

    /** RingBuffer */
    private RingBuffer ringBuffer;
    private BufferPaddingExecutor bufferPaddingExecutor;

    @Override
    public void afterPropertiesSet() throws Exception {
        // initialize workerId & bitsAllocator
        super.afterPropertiesSet();
        
        // initialize RingBuffer & RingBufferPaddingExecutor
        this.initRingBuffer();
        LOGGER.info("Initialized RingBuffer successfully.");
    }
    
    @Override
    public long getUID() {
        try {
            return ringBuffer.take(); // 举例：RingBuffer [bufferSize=65536, tail=71114, cursor=33108, paddingThreshold=32768]
        } catch (Exception e) {
            LOGGER.error("Generate unique id exception. ", e);
            throw new UidGenerateException(e);
        }
    }

    @Override
    public String parseUID(long uid) {
        return super.parseUID(uid);
    }
    
    @Override
    public void destroy() throws Exception {
        bufferPaddingExecutor.shutdown();
    }

    /**
     * Get the UIDs in the same specified second under the max sequence
     * 返回指定1s内，有序的UID集合, 这里每次都会产生MaxSequence+1个uid
     * @param currentSecond
     * @return UID list, size of {@link BitsAllocator#getMaxSequence()} + 1
     */
    protected List<Long> nextIdsForOneSecond(long currentSecond) {
        // Initialize result list size of (max sequence + 1)
        int listSize = (int) bitsAllocator.getMaxSequence() + 1; // 8192
        List<Long> uidList = new ArrayList<>(listSize);
        // 分配一秒内第一个序列，其他序列可以用偏移量计算
        // Allocate the first sequence of the second, the others can be calculated with the offset
        long firstSeqUid = bitsAllocator.allocate(currentSecond - epochSeconds, workerId, 0L);
        for (int offset = 0; offset < listSize; offset++) {
            uidList.add(firstSeqUid + offset); //计算出这一秒内的所有可用有序集合：UIDs
        }

        return uidList;
    }
    
    /**
     * Initialize RingBuffer & RingBufferPaddingExecutor
     */
    private void initRingBuffer() {
        // initialize RingBuffer
        int bufferSize = ((int) bitsAllocator.getMaxSequence() + 1) << boostPower;  // 默认8192*8=2^13 * 2^3=65536
        this.ringBuffer = new RingBuffer(bufferSize, paddingFactor);
        LOGGER.info("Initialized ring buffer size:{}, paddingFactor:{}", bufferSize, paddingFactor);

        // initialize RingBufferPaddingExecutor
        boolean usingSchedule = (scheduleInterval != null);
        this.bufferPaddingExecutor = new BufferPaddingExecutor(ringBuffer, this::nextIdsForOneSecond, usingSchedule);
        if (usingSchedule) {
            bufferPaddingExecutor.setScheduleInterval(scheduleInterval);
        }
        
        LOGGER.info("Initialized BufferPaddingExecutor. Using schdule:{}, interval:{}", usingSchedule, scheduleInterval);
        
        // set rejected put/take handle policy
        this.ringBuffer.setBufferPaddingExecutor(bufferPaddingExecutor);
        if (rejectedPutBufferHandler != null) {
            this.ringBuffer.setRejectedPutHandler(rejectedPutBufferHandler);
        }
        if (rejectedTakeBufferHandler != null) {
            this.ringBuffer.setRejectedTakeHandler(rejectedTakeBufferHandler);
        }
        
        // fill in all slots of the RingBuffer
        bufferPaddingExecutor.paddingBuffer();
        
        // start buffer padding threads
        bufferPaddingExecutor.start();
    }

    /**
     * Setters for spring property
     */
    public void setBoostPower(int boostPower) {
        Assert.isTrue(boostPower > 0, "Boost power must be positive!");
        this.boostPower = boostPower;
    }
    
    public void setRejectedPutBufferHandler(RejectedPutBufferHandler rejectedPutBufferHandler) {
        Assert.notNull(rejectedPutBufferHandler, "RejectedPutBufferHandler can't be null!");
        this.rejectedPutBufferHandler = rejectedPutBufferHandler;
    }

    public void setRejectedTakeBufferHandler(RejectedTakeBufferHandler rejectedTakeBufferHandler) {
        Assert.notNull(rejectedTakeBufferHandler, "RejectedTakeBufferHandler can't be null!");
        this.rejectedTakeBufferHandler = rejectedTakeBufferHandler;
    }

    public void setScheduleInterval(long scheduleInterval) {
        Assert.isTrue(scheduleInterval > 0, "Schedule interval must positive!");
        this.scheduleInterval = scheduleInterval;
    }

    public void setPaddingFactor(int paddingFactor) {
        this.paddingFactor=paddingFactor;
    }
}
