package com.jeffmony.gif;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.jeffmony.gif.listener.IGifMakerListener;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GifMaker {

    public static final String TAG = GifMaker.class.getSimpleName();
    private static final int MSG_WORK_DONE = 100;
    private static final int MSG_ONE_TASK_WORK_DONE = 200;

    private ByteArrayOutputStream mFinalOutputStream;  // 最终合并输出流
    private List<LZWEncoderOrderHolder> mEncodeOrders; // 存放线程处理结果，待全部线程执行完使用
    private String mOutputPath;                        // GIF 保存路径
    private Handler mHandler;                          // 回调回主线程使用
    private ExecutorService mExecutor;                 // 线程池
    private int mCurrentWorkSize;                      // 当前剩余任务长度
    private int mTotalWorkSize;                        // 总任务长度
    private int mDelayTime;                            // 每帧延时

    private IGifMakerListener mOnGifMakerListener;

    public GifMaker(String outPath, int delayTime, IGifMakerListener listener) {
        this(outPath, delayTime, Executors.newCachedThreadPool(), listener);
    }

    public GifMaker(String outPath, int delayTime, ExecutorService executor, IGifMakerListener listener) {
        mFinalOutputStream = new ByteArrayOutputStream();
        mEncodeOrders = new ArrayList<>();
        mExecutor = executor;
        mDelayTime = delayTime;
        mOutputPath = outPath;
        mOnGifMakerListener = listener;

        mHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(final Message msg) {
                if (mOnGifMakerListener == null) {
                    return;
                }
                switch (msg.what) {
                    case MSG_ONE_TASK_WORK_DONE:
                        mOnGifMakerListener.onOneTaskWorkDone();
                        break;
                    case MSG_WORK_DONE:
                        mOnGifMakerListener.onMakeGifSucceed(mOutputPath);
                        break;
                    default:
                        break;
                }
            }
        };
    }

    public void makeGifInThread(Bitmap bitmap, int index, boolean isLast) {
        if (bitmap == null || bitmap.getHeight() == 0 || bitmap.getWidth() == 0) {
            return;
        }

        if (isLast) {
            mTotalWorkSize = index + 1;
        }

        mExecutor.execute(new EncodeGifRunnable(bitmap, index));
    }

    public boolean hasPushLastThread() {
        return mTotalWorkSize > 0;
    }

    /**
     * 编码一帧
     */
    private class EncodeGifRunnable implements Runnable {

        int mOrder; // 当前顺序
        Bitmap mBitmap; // 当前位图
        ThreadGifEncoder mThreadGifEncoder; // 当前编码器
        ByteArrayOutputStream mCurrentOutputStream; // 当前数据输出流

        EncodeGifRunnable(Bitmap bitmap, int order) {
            mCurrentOutputStream = new ByteArrayOutputStream();
            mThreadGifEncoder = new ThreadGifEncoder();
            //mThreadGifEncoder.setQuality(100);
            mThreadGifEncoder.setDelay(mDelayTime);
            mThreadGifEncoder.start(mCurrentOutputStream, order);
            mThreadGifEncoder.setFirstFrame(order == 0);
            mThreadGifEncoder.setRepeat(0);
            mBitmap = bitmap;
            mOrder = order;
        }

        @Override
        public void run() {
            try {
                LZWEncoderOrderHolder holder = mThreadGifEncoder.addFrame(mBitmap, mOrder);
                mThreadGifEncoder.finishThread(mTotalWorkSize > 0 && mOrder == (mTotalWorkSize - 1), holder.getLZWEncoder());
                holder.setByteArrayOutputStream(mCurrentOutputStream);
                mEncodeOrders.add(holder);
                workDone();
                mHandler.sendEmptyMessage(MSG_ONE_TASK_WORK_DONE);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // 完成一帧
    private synchronized void workDone() throws IOException {
        mCurrentWorkSize++;
        if (mCurrentWorkSize == mTotalWorkSize && mTotalWorkSize > 0) {
            //排序 默认从小到大
            Collections.sort(mEncodeOrders);
            for (LZWEncoderOrderHolder myLZWEncoder : mEncodeOrders) {
                mFinalOutputStream.write(myLZWEncoder.getByteArrayOutputStream().toByteArray());
            }
            // mFinalOutputStream.write(0x3b); // gif traile
            byte[] data = mFinalOutputStream.toByteArray();
            File file = new File(mOutputPath);
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            BufferedOutputStream bosToFile = new BufferedOutputStream(new FileOutputStream(file));
            bosToFile.write(data);
            bosToFile.flush();
            bosToFile.close();
            mHandler.sendEmptyMessage(MSG_WORK_DONE);
        }
    }

}
