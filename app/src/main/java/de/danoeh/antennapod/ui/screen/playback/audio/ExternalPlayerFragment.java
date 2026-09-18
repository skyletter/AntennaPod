package de.danoeh.antennapod.ui.screen.playback.audio;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.URLUtil;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import de.danoeh.antennapod.BuildConfig;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.event.SyncServiceEvent;
import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
import de.danoeh.antennapod.event.playback.PlaybackServiceEvent;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.MediaType;
import de.danoeh.antennapod.model.playback.Playable;
import de.danoeh.antennapod.net.common.NetworkUtils;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.playback.service.PlaybackService;
import de.danoeh.antennapod.playback.service.PlaybackServiceStarter;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.SynchronizationSettings;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.appstartintent.MediaButtonStarter;
import de.danoeh.antennapod.ui.episodes.ImageResourceUtils;
import de.danoeh.antennapod.ui.screen.playback.PlayButton;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * Fragment which is supposed to be displayed outside of the MediaplayerActivity.
 */
public class ExternalPlayerFragment extends Fragment {
    public static final String TAG = "ExternalPlayerFragment";
    private static final long AUTO_PLAY_SYNC_TIMEOUT_MS = 15000;

    private ImageView imgvCover;
    private TextView txtvTitle;
    private PlayButton butPlay;
    private TextView feedName;
    private ProgressBar progressBar;
    private Disposable disposable;
    private Playable currentMedia;
    private boolean autoPlayOnStartPending;
    private boolean autoPlayWaitingForSync;
    private boolean autoPlayAfterReload;
    private final Handler autoPlayHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoPlaySyncTimeout = this::continueAutoPlayAfterSync;

    public ExternalPlayerFragment() {
        super();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        autoPlayOnStartPending = savedInstanceState == null && UserPreferences.isAutoPlayOnStart();
        View root = inflater.inflate(R.layout.external_player_fragment, container, false);
        imgvCover = root.findViewById(R.id.imgvCover);
        txtvTitle = root.findViewById(R.id.txtvTitle);
        butPlay = root.findViewById(R.id.butPlay);
        feedName = root.findViewById(R.id.txtvAuthor);
        progressBar = root.findViewById(R.id.episodeProgress);

        root.findViewById(R.id.fragmentLayout).setOnClickListener(v -> {
            Log.d(TAG, "layoutInfo was clicked");

            if (currentMedia != null) {
                if (currentMedia.getMediaType() == MediaType.AUDIO) {
                    ((MainActivity) getActivity()).getBottomSheet().setState(BottomSheetBehavior.STATE_EXPANDED);
                } else {
                    Intent intent = PlaybackService.getPlayerActivityIntent(getActivity(), currentMedia);
                    startActivity(intent);
                }
            }
        });
        butPlay.setOnClickListener(v -> {
            if (PlaybackService.isRunning
                    && PlaybackPreferences.getCurrentPlayerStatus() == PlaybackPreferences.PLAYER_STATUS_PLAYING) {
                if (BuildConfig.USE_MEDIA3_PLAYBACK_SERVICE) {
                    PlaybackController.bindToMedia3Service(getContext(), controller -> controller.pause());
                } else {
                    getContext().sendBroadcast(
                            MediaButtonStarter.createIntent(getContext(), KeyEvent.KEYCODE_MEDIA_PAUSE));
                }
            } else {
                new PlaybackServiceStarter(getContext(), currentMedia)
                        .callEvenIfRunning(true)
                        .start();
            }
        });
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        loadMediaInfo();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusEvent(PlayerStatusEvent event) {
        loadMediaInfo();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPositionObserverUpdate(PlaybackPositionEvent event) {
        if (event.getPosition() == Playable.INVALID_TIME || event.getDuration() == Playable.INVALID_TIME) {
            return;
        }
        progressBar.setProgress((int) ((double) event.getPosition() / event.getDuration() * 100));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlaybackServiceChanged(PlaybackServiceEvent event) {
        if (event.action == PlaybackServiceEvent.Action.SERVICE_SHUT_DOWN) {
            ((MainActivity) getActivity()).setPlayerVisible(false);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Fragment is about to be destroyed");
        autoPlayHandler.removeCallbacks(autoPlaySyncTimeout);
        autoPlayWaitingForSync = false;
        if (disposable != null) {
            disposable.dispose();
        }
    }

    private void loadMediaInfo() {
        Log.d(TAG, "Loading media info");

        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Maybe.fromCallable(
                        () -> DBReader.getFeedMedia(PlaybackPreferences.getCurrentlyPlayingFeedMediaId()))
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::updateUi,
                        error -> Log.e(TAG, Log.getStackTraceString(error)),
                        () -> {
                            autoPlayAfterReload = false;
                            ((MainActivity) getActivity()).setPlayerVisible(false);
                        });
    }

    private void updateUi(Playable media) {
        if (media == null) {
            return;
        }
        currentMedia = media;
        ((MainActivity) getActivity()).setPlayerVisible(true);
        txtvTitle.setText(media.getEpisodeTitle());
        feedName.setText(media.getFeedTitle());
        onPositionObserverUpdate(new PlaybackPositionEvent(media.getPosition(), media.getDuration()));
        boolean isPlaying = PlaybackService.isRunning
                && PlaybackPreferences.getCurrentPlayerStatus() == PlaybackPreferences.PLAYER_STATUS_PLAYING;
        butPlay.setIsShowPlay(!isPlaying);

        RequestOptions options = new RequestOptions()
                .placeholder(R.color.light_gray)
                .error(R.color.light_gray)
                .fitCenter()
                .dontAnimate();

        Glide.with(this)
                .load(ImageResourceUtils.getEpisodeListImageLocation(media))
                .error(Glide.with(this)
                        .load(ImageResourceUtils.getFallbackImageLocation(media))
                        .apply(options))
                .apply(options)
                .into(imgvCover);

        if (currentMedia.getMediaType() == MediaType.VIDEO) {
            butPlay.setVisibility(View.GONE);
            ((MainActivity) getActivity()).getBottomSheet().setLocked(true);
            ((MainActivity) getActivity()).getBottomSheet().setState(BottomSheetBehavior.STATE_COLLAPSED);
        } else {
            butPlay.setVisibility(View.VISIBLE);
            ((MainActivity) getActivity()).getBottomSheet().setLocked(false);
        }

        if (autoPlayOnStartPending) {
            autoPlayOnStartPending = false;
            autoPlayOnStart(currentMedia);
        } else if (autoPlayAfterReload) {
            autoPlayAfterReload = false;
            startAutoPlay(currentMedia);
        }
    }

    private void autoPlayOnStart(Playable media) {
        if (PlaybackService.isRunning || PlaybackService.isCasting()) {
            return;
        }
        if (media.getMediaType() != MediaType.AUDIO) {
            return;
        }
        if (media instanceof FeedMedia && needsStreamingConfirmation((FeedMedia) media)) {
            return;
        }
        if (UserPreferences.isAutoPlayWaitForSync() && SynchronizationSettings.isProviderConnected()) {
            autoPlayWaitingForSync = true;
            SynchronizationQueue.getInstance().syncImmediately();
            autoPlayHandler.postDelayed(autoPlaySyncTimeout, AUTO_PLAY_SYNC_TIMEOUT_MS);
            return;
        }
        startAutoPlay(media);
    }

    private void startAutoPlay(Playable media) {
        new PlaybackServiceStarter(getContext(), media)
                .callEvenIfRunning(true)
                .start();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSyncServiceEvent(SyncServiceEvent event) {
        if (!autoPlayWaitingForSync) {
            return;
        }
        if (event.getMessageResId() == R.string.sync_status_success
                || event.getMessageResId() == R.string.sync_status_error) {
            continueAutoPlayAfterSync();
        }
    }

    private void continueAutoPlayAfterSync() {
        if (!autoPlayWaitingForSync) {
            return;
        }
        autoPlayWaitingForSync = false;
        autoPlayHandler.removeCallbacks(autoPlaySyncTimeout);
        // Reload, so that the position that was just pulled from the server is used
        autoPlayAfterReload = true;
        loadMediaInfo();
    }

    private static boolean needsStreamingConfirmation(FeedMedia media) {
        return !media.localFileAvailable()
                && !URLUtil.isContentUrl(media.getStreamUrl())
                && !NetworkUtils.isStreamingAllowed();
    }
}
