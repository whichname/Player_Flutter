// Copyright (c) 2022 Tencent. All rights reserved.

package com.tencent.vod.flutter.ui;

import android.app.PictureInPictureParams;
import android.app.PictureInPictureUiState;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ProgressBar;
import androidx.annotation.NonNull;
import com.tencent.rtmp.ITXLivePlayListener;
import com.tencent.rtmp.ITXVodPlayListener;
import com.tencent.rtmp.TXLiveConstants;
import com.tencent.rtmp.TXLivePlayer;
import com.tencent.rtmp.TXPlayInfoParams;
import com.tencent.rtmp.TXVodPlayer;
import com.tencent.vod.flutter.FTXEvent;
import com.tencent.vod.flutter.FTXPIPManager.PipParams;
import com.tencent.vod.flutter.model.PipResult;
import com.tencent.vod.flutter.model.VideoModel;
import com.tencent.vod.flutter.R;
import io.flutter.embedding.android.FlutterActivity;

public class FlutterPipImplActivity extends FlutterActivity implements Callback, ITXVodPlayListener,
        ITXLivePlayListener {

    private static final String TAG = "FlutterPipImplActivity";

    /**
     * 这里使用needToExitPip作为标志位，在出现onPictureInPictureModeChanged回调画中画状态和isInPictureInPictureMode不一致的时候。
     * 标志位true，然后在onConfigurationChanged监听到界面宽高发生变化的时候，进行画中画模式退出的事件通知。
     * for MIUI 12.5.1
     */
    private boolean needToExitPip = false;
    private int configWidth = 0;
    private int configHeight = 0;

    private SurfaceView mVideoSurface;
    private ProgressBar mVideoProgress;

    private TXVodPlayer mVodPlayer;
    private TXLivePlayer mLivePlayer;
    private boolean mIsSurfaceCreated = false;
    // 画中画，点击右上角X，会先触发onStop，点击放大按钮，不会触发onStop
    private boolean mIsNeedToStop = false;
    private VideoModel mVideoModel;
    private boolean mIsRegisterReceiver = false;
    private PipParams mCurrentParams;

    private final BroadcastReceiver pipActionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle data = intent.getExtras();
            if (null != data && null != mCurrentParams) {
                int playerId = data.getInt(FTXEvent.EXTRA_NAME_PLAYER_ID, -1);
                if (playerId == mCurrentParams.getCurrentPlayerId()) {
                    int controlCode = data.getInt(FTXEvent.EXTRA_NAME_PLAY_OP, -1);
                    switch (controlCode) {
                        case FTXEvent.EXTRA_PIP_PLAY_BACK:
                            handlePlayBack();
                            break;
                        case FTXEvent.EXTRA_PIP_PLAY_RESUME_OR_PAUSE:
                            handleResumeOrPause();
                            break;
                        case FTXEvent.EXTRA_PIP_PLAY_FORWARD:
                            handlePlayForward();
                            break;
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerPipBroadcast();
        Intent intent = getIntent();
        PipParams params = intent.getParcelableExtra(FTXEvent.EXTRA_NAME_PARAMS);
        if (null == params) {
            Log.e(TAG, "lack pip params,please check the intent argument");
            finish();
        } else {
            mCurrentParams = params;
            if (VERSION.SDK_INT >= VERSION_CODES.O) {
                configPipMode(params.buildParams(this));
            } else {
                configPipMode(null);
            }
        }
        handleIntent(intent);
        setContentView(R.layout.activity_flutter_pip_impl);
        mVodPlayer = new TXVodPlayer(this);
        mLivePlayer = new TXLivePlayer(this);
        mVideoSurface = findViewById(R.id.sv_video_container);
        mVideoProgress = findViewById(R.id.pb_video_progress);
        mVideoSurface.getHolder().addCallback(this);
        setVodPlayerListener();
        setLivePlayerListener();
    }

    private void setVodPlayerListener() {
        mVodPlayer.setVodListener(this);
    }

    private void setLivePlayerListener() {
        mLivePlayer.setPlayListener(this);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            boolean isInPictureInPictureMode = isInPictureInPictureMode();
            if (isInPictureInPictureMode) {
                configWidth = newConfig.screenWidthDp;
                configHeight = newConfig.screenHeightDp;
            } else if (needToExitPip && configWidth != newConfig.screenWidthDp
                    && configHeight != newConfig.screenHeightDp) {
                handlePipExitEvent();
                needToExitPip = false;
            }
        }
    }

    /**
     * 为了兼容MIUI 12.5，PIP模式下，打开其他app然后上滑退出，再点击画中画窗口，onPictureInPictureModeChanged会异常回调关闭的情况
     *
     * @param ignore 校对画中画状态
     */
    @Override
    public void onPictureInPictureModeChanged(boolean ignore) {
        boolean isInPictureInPictureMode = ignore;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            isInPictureInPictureMode = isInPictureInPictureMode();
        }
        if (isInPictureInPictureMode != ignore) {
            needToExitPip = true;
        } else {
            if (isInPictureInPictureMode) {
                sendPipBroadCast(FTXEvent.EVENT_PIP_MODE_ALREADY_ENTER, null);
                showComponent();
            } else {
                handlePipExitEvent();
            }
        }
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
    }

    @Override
    public void onPictureInPictureUiStateChanged(@NonNull PictureInPictureUiState pipState) {
        super.onPictureInPictureUiStateChanged(pipState);
        sendPipBroadCast(FTXEvent.EVENT_PIP_MODE_UI_STATE_CHANGED, null);
    }

    /**
     * enterPictureInPictureMode生效后的回调通知，only for android > 31
     */
    @Override
    public boolean onPictureInPictureRequested() {
        return super.onPictureInPictureRequested();
    }

    @Override
    public boolean enterPictureInPictureMode(@NonNull PictureInPictureParams params) {
        return super.enterPictureInPictureMode(params);
    }

    private void configPipMode(PictureInPictureParams params) {
        if (VERSION.SDK_INT >= VERSION_CODES.N) {
            if (VERSION.SDK_INT >= VERSION_CODES.O) {
                enterPictureInPictureMode(params);
            } else {
                enterPictureInPictureMode();
            }
        }
    }

    private void registerPipBroadcast() {
        if (!mIsRegisterReceiver) {
            IntentFilter pipIntentFilter = new IntentFilter(FTXEvent.ACTION_PIP_PLAY_CONTROL);
            registerReceiver(pipActionReceiver, pipIntentFilter);
            mIsRegisterReceiver = true;
        }
    }

    private void unRegisterPipBroadcast() {
        if (mIsRegisterReceiver) {
            unregisterReceiver(pipActionReceiver);
        }
    }

    private void handlePipExitEvent() {
        Bundle data = new Bundle();
        PipResult pipResult = new PipResult();
        if (mVideoModel.getPlayerType() == FTXEvent.PLAYER_VOD) {
            Float currentPlayTime = mVodPlayer.getCurrentPlaybackTime();
            pipResult.setPlayTime(currentPlayTime);
            pipResult.setPlaying(mVodPlayer.isPlaying());
            pipResult.setPlayerId(mCurrentParams.getCurrentPlayerId());
            data.putParcelable(FTXEvent.EXTRA_NAME_RESULT, pipResult);
        } else if (mVideoModel.getPlayerType() == FTXEvent.PLAYER_LIVE) {
            pipResult.setPlaying(mLivePlayer.isPlaying());
            pipResult.setPlayerId(mCurrentParams.getCurrentPlayerId());
            data.putParcelable(FTXEvent.EXTRA_NAME_RESULT, pipResult);
        }
        int codeEvent = mIsNeedToStop ? FTXEvent.EVENT_PIP_MODE_ALREADY_EXIT : FTXEvent.EVENT_PIP_MODE_RESTORE_UI;
        sendPipBroadCast(codeEvent, data);
        overridePendingTransition(0, 0);
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null) {
            String action = intent.getAction();
            if (TextUtils.equals(action, FTXEvent.PIP_ACTION_START)) {
                startPipVideoFromIntent(intent);
            } else if (TextUtils.equals(action, FTXEvent.PIP_ACTION_EXIT)) {
                exitPip();
            } else if (TextUtils.equals(action, FTXEvent.PIP_ACTION_UPDATE)) {
                PipParams pipParams = intent.getParcelableExtra(FTXEvent.EXTRA_NAME_PARAMS);
                updatePip(pipParams);
            } else {
                Log.e(TAG, "unknown pip action:" + action);
            }
        }
    }

    private void updatePip(PipParams pipParams) {
        if (null != pipParams) {
            mCurrentParams = pipParams;
            if (VERSION.SDK_INT >= VERSION_CODES.O) {
                setPictureInPictureParams(pipParams.buildParams(this));
            }
        }
    }

    private void exitPip() {
        finish();
    }

    private void startPipVideoFromIntent(Intent intent) {
        mVideoModel = (VideoModel) intent.getParcelableExtra(FTXEvent.EXTRA_NAME_VIDEO);
        if (mIsSurfaceCreated) {
            attachSurface(mVideoSurface.getHolder().getSurface());
            startPlay();
        }
    }

    private void startPlay() {
        if (null != mVideoModel) {
            float playTime = mCurrentParams.getCurrentPlayTime();
            boolean isPlaying = mCurrentParams.isPlaying();
            if (mVideoModel.getPlayerType() == FTXEvent.PLAYER_VOD) {
                mVodPlayer.setStartTime(playTime);
                mVodPlayer.setAutoPlay(isPlaying);
                if (!TextUtils.isEmpty(mVideoModel.getVideoUrl())) {
                    mVodPlayer.startVodPlay(mVideoModel.getVideoUrl());
                } else if (!TextUtils.isEmpty(mVideoModel.getFileId())) {
                    mVodPlayer.startVodPlay(
                            new TXPlayInfoParams(mVideoModel.getAppId(), mVideoModel.getFileId(),
                                    mVideoModel.getPSign()));
                }
            } else if (mVideoModel.getPlayerType() == FTXEvent.PLAYER_LIVE) {
                mVideoProgress.setProgress(mVideoProgress.getMax());
                mLivePlayer.startLivePlay(mVideoModel.getVideoUrl(), mVideoModel.getLiveType());
                // 直播暂时不支持一开始画中画直播暂停
                mCurrentParams.setIsPlaying(true);
                if (VERSION.SDK_INT >= VERSION_CODES.O) {
                    setPictureInPictureParams(mCurrentParams.buildParams(this));
                }
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mIsSurfaceCreated = true;
        attachSurface(holder.getSurface());
        startPlay();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mVodPlayer.setSurface(null);
        mLivePlayer.setSurface(null);
        mIsSurfaceCreated = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        unRegisterPipBroadcast();
        mVodPlayer.stopPlay(true);
        mLivePlayer.stopPlay(true);
        mIsNeedToStop = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        mIsNeedToStop = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void attachSurface(Surface surface) {
        if (null != mVideoModel) {
            if (mVideoModel.getPlayerType() == FTXEvent.PLAYER_VOD) {
                mVodPlayer.setSurface(surface);
            } else if (mVideoModel.getPlayerType() == FTXEvent.PLAYER_LIVE) {
                mLivePlayer.setSurface(surface);
            } else {
                Log.e(TAG, "unknown player type:" + mVideoModel.getPlayerType());
            }
        } else {
            Log.e(TAG, "pip video model is null");
        }
    }

    private void handlePlayBack() {
        if (mVodPlayer.isPlaying()) {
            float backPlayTime = mVodPlayer.getCurrentPlaybackTime() - 10;
            if (backPlayTime < 0) {
                backPlayTime = 0;
            }
            mVodPlayer.seek(backPlayTime);
        }
    }

    private void handleResumeOrPause() {
        boolean dstPlaying = !mVodPlayer.isPlaying();
        if (mVideoModel.getPlayerType() == FTXEvent.PLAYER_VOD) {
            if (dstPlaying) {
                mVodPlayer.resume();
            } else {
                mVodPlayer.pause();
            }
        } else if (mVideoModel.getPlayerType() == FTXEvent.PLAYER_LIVE) {
            if (dstPlaying) {
                mLivePlayer.resume();
            } else {
                mLivePlayer.pause();
            }
        }
        mCurrentParams.setIsPlaying(dstPlaying);
        updatePip(mCurrentParams);
    }

    private void handlePlayForward() {
        if (mVodPlayer.isPlaying()) {
            float forwardPlayTime = mVodPlayer.getCurrentPlaybackTime() + 10;
            float duration = mVodPlayer.getDuration();
            if (forwardPlayTime > duration) {
                forwardPlayTime = duration;
            }
            mVodPlayer.seek(forwardPlayTime);
        }
    }

    private void sendPipBroadCast(int eventCode, Bundle data) {
        Intent intent = new Intent();
        intent.setAction(FTXEvent.EVENT_PIP_ACTION);
        intent.putExtra(FTXEvent.EVENT_PIP_MODE_NAME, eventCode);
        intent.putExtra(FTXEvent.EXTRA_NAME_PLAYER_ID, mCurrentParams.getCurrentPlayerId());
        if (null != data) {
            intent.putExtras(data);
        }
        sendBroadcast(intent);
    }

    /**
     * 显示组件
     * 为了防止画中画启动一瞬间的黑屏，组件一开始为隐藏状态，只有进入画中画之后才会显示组件
     */
    private void showComponent() {
        mVideoSurface.setVisibility(View.VISIBLE);
        mVideoProgress.setVisibility(View.VISIBLE);
    }

    @Override
    public void onPlayEvent(TXVodPlayer txVodPlayer, int event, Bundle bundle) {
        if (VERSION.SDK_INT >= VERSION_CODES.N && isInPictureInPictureMode()) {
            if(null != mCurrentParams) {
                if (event == TXLiveConstants.PLAY_EVT_PLAY_END) {
                    // 播放完毕的时候，自动将播放按钮置为播放
                    mCurrentParams.setIsPlaying(false);
                } else if(event == TXLiveConstants.PLAY_EVT_PLAY_BEGIN) {
                    // 播放开始的时候，自动将播放按钮置为暂停
                    mCurrentParams.setIsPlaying(true);
                }
                updatePip(mCurrentParams);
            }
            if(event == TXLiveConstants.PLAY_EVT_PLAY_PROGRESS) {
                int progress = bundle.getInt(TXLiveConstants.EVT_PLAY_PROGRESS_MS);
                int duration = bundle.getInt(TXLiveConstants.EVT_PLAY_DURATION_MS);
                float percentage = (progress / 1000F) / (duration / 1000F);
                final int progressToShow = Math.round(percentage * mVideoProgress.getMax());
                if(null != mVideoProgress) {
                    mVideoProgress.post(new Runnable() {
                        @Override
                        public void run() {
                            mVideoProgress.setProgress(progressToShow);
                        }
                    });
                }
            }
        }
    }

    @Override
    public void onNetStatus(TXVodPlayer txVodPlayer, Bundle bundle) {
    }

    @Override
    public void onPlayEvent(int event, Bundle bundle) {
    }

    @Override
    public void onNetStatus(Bundle bundle) {
    }
}