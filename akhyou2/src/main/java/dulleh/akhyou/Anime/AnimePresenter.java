package dulleh.akhyou.Anime;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.graphics.Palette;

import java.util.List;

import de.greenrobot.event.EventBus;
import dulleh.akhyou.Models.AnimeProviders.BamAnimeProvider;
import dulleh.akhyou.Models.AnimeProviders.KissAnimeProvider;
import dulleh.akhyou.Models.AnimeProviders.RamAnimeProvider;
import dulleh.akhyou.Models.AnimeProviders.RushAnimeProvider;
import dulleh.akhyou.Models.AnimeProviders.AnimeProvider;
import dulleh.akhyou.Models.Anime;
import dulleh.akhyou.Models.Source;
import dulleh.akhyou.Models.Video;
import dulleh.akhyou.Utils.CloudFlareInitializationException;
import dulleh.akhyou.Utils.CloudflareHttpClient;
import dulleh.akhyou.Utils.Events.FavouriteEvent;
import dulleh.akhyou.Utils.Events.LastAnimeEvent;
import dulleh.akhyou.Utils.Events.OpenAnimeEvent;
import dulleh.akhyou.Utils.Events.SnackbarEvent;
import dulleh.akhyou.Utils.GeneralUtils;
import nucleus.presenter.RxPresenter;
import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Func0;
import rx.schedulers.Schedulers;

public class AnimePresenter extends RxPresenter<AnimeFragment>{
    private static final String LAST_ANIME_BUNDLE_KEY = "last_anime";

    private Subscription animeSubscription;
    private Subscription episodeSubscription;
    private Subscription videoSubscription;
    private AnimeProvider animeProvider;

    Anime lastAnime;
    public boolean isRefreshing;
    private static boolean needToGiveFavouriteState = false;

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);

        if (savedState != null && savedState.containsKey(LAST_ANIME_BUNDLE_KEY)) {
            lastAnime = savedState.getParcelable(LAST_ANIME_BUNDLE_KEY);
        }

    }

    @Override
    protected void onTakeView(AnimeFragment view) {
        super.onTakeView(view);
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().registerSticky(this);
        }

        view.updateRefreshing();

        if (lastAnime != null && lastAnime.getUrl() != null) {

            if (lastAnime.getEpisodes() != null) {
                view.setAnime(lastAnime);
            } else if (lastAnime.getTitle() != null) {
                view.setToolbarTitle(lastAnime.getTitle());
            }

            if (needToGiveFavouriteState) {
                view.setFavouriteChecked(view.isInFavourites(lastAnime));
                needToGiveFavouriteState = false;
            }

        }
    }

    @Override
    protected void onSave(Bundle state) {
        super.onSave(state);
        if (lastAnime != null && lastAnime.getEpisodes() != null) {
            state.putParcelable(LAST_ANIME_BUNDLE_KEY, lastAnime);
            EventBus.getDefault().post(new LastAnimeEvent(lastAnime));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        animeProvider = null;
        unsubscribe();
    }

    private void unsubscribe () {
        if (animeSubscription != null && !animeSubscription.isUnsubscribed()) {
            animeSubscription.unsubscribe();
        }
        if (episodeSubscription != null && !episodeSubscription.isUnsubscribed()) {
            episodeSubscription.unsubscribe();
        }
        if (videoSubscription != null && !videoSubscription.isUnsubscribed()) {
            videoSubscription.unsubscribe();
        }
    }

    private Anime setAnimeProvider (Anime anime) {
        if (anime.getProviderType() != null) {
            switch (anime.getProviderType()) {
                case Anime.ANIME_RUSH:
                    animeProvider = new RushAnimeProvider();
                    break;
                case Anime.ANIME_RAM:
                    animeProvider = new RamAnimeProvider();
                    break;
                case Anime.ANIME_BAM:
                    animeProvider = new BamAnimeProvider();
                    break;
                case Anime.ANIME_KISS:
                    animeProvider = new KissAnimeProvider();
                    break;
                default:
                    try {
                        anime.setProviderType(GeneralUtils.determineProviderType(anime.getUrl()));
                        setAnimeProvider(anime);
                    } catch (Exception e) {
                        postError(e);
                    }
                    break;
            }
        } else {
            try {
                anime.setProviderType(GeneralUtils.determineProviderType(anime.getUrl()));
                setAnimeProvider(anime);
            } catch (Exception e) {
                postError(e);
            }
        }
        return anime;
    }

    public void onEvent (OpenAnimeEvent event) {
        if (lastAnime == null || !event.anime.getProviderType().equals(lastAnime.getProviderType())) {
            lastAnime = setAnimeProvider(event.anime);
        } else {
            lastAnime = event.anime;
        }

        if (lastAnime != null && lastAnime.getEpisodes() != null) {
            getView().setAnime(lastAnime);
            fetchAnime(true);
        } else {
            fetchAnime(false);
        }
    }

    public void fetchAnime (boolean updateCached) {
        isRefreshing = true;
        if (getView() != null) {
            getView().updateRefreshing();
        }

        if (animeSubscription != null && !animeSubscription.isUnsubscribed()) {
            animeSubscription.unsubscribe();
        }

        animeSubscription = Observable.defer(new Func0<Observable<Anime>>() {
            @Override
            public Observable<Anime> call() {
                if (updateCached) {
                    try {
                        return Observable.just(animeProvider.updateCachedAnime(lastAnime));
                    } catch (CloudFlareInitializationException cf) {
                            CloudflareHttpClient.INSTANCE.forceSolve = true;
                            CloudflareHttpClient.INSTANCE.registerSites();
                            return Observable.error(new Throwable("Wait 5 seconds and try again (or don't)."));
                    }
                }
                try {
                    return Observable.just(animeProvider.fetchAnime(lastAnime.getUrl()));
                } catch (CloudFlareInitializationException cf) {
                    CloudflareHttpClient.INSTANCE.forceSolve = true;
                    CloudflareHttpClient.INSTANCE.registerSites();
                    return Observable.error(new Throwable("Wait 5 seconds and try again (or don't)."));
                }
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(this.deliverLatestCache())
                .subscribe(new Subscriber<Anime>() {
                    @Override
                    public void onNext(Anime anime) {
                        // for AnimeBam which doesn't have alt-title on anime page
                        if (lastAnime.getUrl().equals(anime.getUrl()) && lastAnime.getAlternateTitle() != null && anime.getAlternateTitle() == null) {
                            anime.setAlternateTitle(lastAnime.getAlternateTitle());
                        }
                        lastAnime = anime;
                        isRefreshing = false;
                        getView().setAnime(lastAnime);
                        //EventBus.getDefault().post(new LastAnimeEvent(lastAnime)); would save it without a major colour
                        this.unsubscribe();
                    }

                    @Override
                    public void onCompleted() {
                        // should be using Observable.just() as onCompleted is never called
                        // and it only runs once.
                    }

                    @Override
                    public void onError(Throwable e) {
                        isRefreshing = false;
                        getView().updateRefreshing();
                        postError(e);
                        this.unsubscribe();
                    }

                });
    }

    public void setNeedToGiveFavourite (boolean bool) {
        needToGiveFavouriteState = bool;
    }

    public void setMajorColour (Palette palette) {
        if (palette != null) {
            if (palette.getVibrantSwatch() != null) {
                lastAnime.setMajorColour(palette.getVibrantSwatch().getRgb());
            } else if (palette.getLightVibrantSwatch() != null) {
                lastAnime.setMajorColour(palette.getLightVibrantSwatch().getRgb());
            } else if (palette.getDarkMutedSwatch() != null) {
                lastAnime.setMajorColour(palette.getDarkMutedSwatch().getRgb());
            }
        }
    }

    public void onFavouriteCheckedChanged (boolean b) {
        EventBus.getDefault().post(new FavouriteEvent(b, lastAnime));
    }

    public void downloadOrStream (Video video, boolean download) {
        if (download) {
            GeneralUtils.internalDownload((DownloadManager) getView().getActivity().getSystemService(Context.DOWNLOAD_SERVICE), video.getUrl());
            //GeneralUtils.lazyDownload((AppCompatActivity) getView().getActivity(), video.getUrl());
        } else {
            postIntent(video.getUrl());
        }
    }

    private void postIntent (String videoUrl) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(videoUrl), "video/*");
        if (intent.resolveActivity(getView().getActivity().getPackageManager()) != null) {
            getView().startActivity(intent);
        }
    }

    public void flipWatched (int position) {
        lastAnime.getEpisodes().get(position).flipWatched();
        getView().notifyAdapter();
    }

    public void fetchSources (String url) {
        if (episodeSubscription != null) {
            if (!episodeSubscription.isUnsubscribed()) {
                episodeSubscription.unsubscribe();
            }
        }
        
        episodeSubscription = Observable.defer(new Func0<Observable<List<Source>>>() {
            @Override
            public Observable<List<Source>> call() {
                try {
                    return Observable.just(animeProvider.fetchSources(url));
                } catch (CloudFlareInitializationException cf) {
                    CloudflareHttpClient.INSTANCE.forceSolve = true;
                    CloudflareHttpClient.INSTANCE.registerSites();
                    return Observable.error(new Throwable("Wait 5 seconds and try again (or don't)."));
                }
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(this.deliver())
                .subscribe(new Subscriber<List<Source>>() {
                    @Override
                    public void onNext(List<Source> sources) {
                        getView().showSourcesDialog(sources);
                        episodeSubscription.unsubscribe();
                    }

                    @Override
                    public void onCompleted() {
                        // should be using Observable.just() as onCompleted is never called
                        // and it only runs once.
                    }

                    @Override
                    public void onError(Throwable e) {
                        postError(e);
                        this.unsubscribe();
                    }

                });
    }

    public void fetchVideo (Source source, boolean download) {
        if (videoSubscription != null) {
            if (!videoSubscription.isUnsubscribed()) {
                videoSubscription.unsubscribe();
            }
        }

        videoSubscription = Observable.defer(new Func0<Observable<Source>>() {
            @Override
            public Observable<Source> call() {
                return Observable.just(animeProvider.fetchVideo(source));
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(this.deliver())
                // this subscriber stays here because it needs the 'lazyDownload'
                .subscribe(new Subscriber<Source>() {
                    @Override
                    public void onNext(Source source) {
                        getView().shareVideo(source, download);
                        this.unsubscribe();
                    }

                    @Override
                    public void onCompleted() {
                        // should be using Observable.just() as onCompleted is never called
                        // and it only runs once.
                    }

                    @Override
                    public void onError(Throwable e) {
                        postError(e);
                        this.unsubscribe();
                    }

                });
    }

    public void postError (Throwable e) {
        e.printStackTrace();
        EventBus.getDefault().post(new SnackbarEvent(GeneralUtils.formatError(e)));
    }

    public void postSuccess (String successMessage) {
        EventBus.getDefault().post(new SnackbarEvent(successMessage));
    }

}
