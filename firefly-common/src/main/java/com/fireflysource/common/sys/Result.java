package com.fireflysource.common.sys;

import com.fireflysource.common.string.StringUtils;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.fireflysource.common.func.FunctionInterfaceUtils.createEmptyConsumer;

/**
 * @author Pengtao Qiu
 */
public class Result<T> {

    public static final Result<Void> SUCCESS = new Result<>(true, null, null);
    private static final Consumer EMPTY = createEmptyConsumer();

    private final boolean success;
    private final T value;
    private final Throwable throwable;

    public Result(boolean success, T value, Throwable throwable) {
        this.success = success;
        this.value = value;
        this.throwable = throwable;
    }

    @SuppressWarnings("unchecked")
    public static <T> Consumer<Result<T>> discard() {
        return EMPTY;
    }

    public static <T> Consumer<Result<T>> futureToConsumer(CompletableFuture<T> future) {
        return result -> {
            if (result.isSuccess()) {
                future.complete(result.getValue());
            } else {
                future.completeExceptionally(result.getThrowable());
            }
        };
    }

    public boolean isSuccess() {
        return success;
    }

    public T getValue() {
        return value;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    @Override
    public String toString() {
        return "Result{" +
                "success=" + success +
                ", value=" + value +
                ", throwable=" + (throwable != null && StringUtils.hasText(throwable.getMessage()) ? throwable.getMessage() : "null") +
                '}';
    }
}