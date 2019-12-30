package org.easydarwin.easypusher.util;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import org.reactivestreams.Publisher;

import io.reactivex.Single;
import io.reactivex.subjects.PublishSubject;

public class RxHelper {
    static boolean IGNORE_ERROR = false;

    public static <T> Single<T> single(@NonNull Publisher<T> t, @Nullable T defaultValueIfNotNull) {
        if (defaultValueIfNotNull != null)
            return Single.just(defaultValueIfNotNull);

        final PublishSubject sub = PublishSubject.create();

        t.subscribe(new AbstractSubscriber<T>() {
            @Override
            public void onNext(T t) {
                super.onNext(t);
                sub.onNext(t);
            }

            @Override
            public void onError(Throwable t) {
                if (IGNORE_ERROR) {
                    super.onError(t);
                    sub.onComplete();
                }else {
                    sub.onError(t);
                }
            }

            @Override
            public void onComplete() {
                super.onComplete();
                sub.onComplete();
            }
        });

        return sub.firstOrError();
    }
}
