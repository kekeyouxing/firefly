package com.fireflysource.common.pool

import com.fireflysource.common.concurrent.Atomics
import com.fireflysource.common.coroutine.CoroutineDispatchers.newSingleThreadDispatcher
import com.fireflysource.common.coroutine.launchGlobally
import com.fireflysource.common.func.Callback
import com.fireflysource.common.lifecycle.AbstractLifeCycle
import com.fireflysource.common.sys.SystemLogger
import com.fireflysource.common.track.FixedTimeLeakDetector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.time.withTimeout
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * @author Pengtao Qiu
 */
class AsyncBoundObjectPool<T>(
    private val maxSize: Int,
    private val timeout: Long,
    private val objectFactory: Pool.ObjectFactory<T>,
    private val validator: Pool.Validator<T>,
    private val dispose: Pool.Dispose<T>,
    leakDetectorInterval: Long,
    releaseTimeout: Long,
    noLeakCallback: Callback
) : AbstractLifeCycle(), AsyncPool<T> {

    companion object {
        private val log = SystemLogger.create(AsyncBoundObjectPool::class.java)
        private val objectPoolDispatcher: CoroutineDispatcher = newSingleThreadDispatcher("firefly-object-pool-thread")
    }

    private val createdCount = AtomicInteger(0)
    private val size = AtomicInteger(0)
    private val poolChannel = Channel<PooledObject<T>>(maxSize)
    private val pollTaskChannel = Channel<CompletableFuture<PooledObject<T>>>(Channel.UNLIMITED)
    private val releaseTaskChannel = Channel<PooledObject<T>>(Channel.UNLIMITED)
    private val leakDetector =
        FixedTimeLeakDetector<PooledObject<T>>(leakDetectorInterval, releaseTimeout, noLeakCallback)
    private val pollObjectJob: Job
    private val releaseObjectJob: Job

    init {
        pollObjectJob = launchPollObjectJob()
        releaseObjectJob = launchReleaseObjectJob()
        start()
    }

    override fun take(): PooledObject<T> = poll().get(timeout, TimeUnit.SECONDS)

    override suspend fun getPooledObject(): PooledObject<T> = poll().await()

    override fun poll(): CompletableFuture<PooledObject<T>> {
        val future = CompletableFuture<PooledObject<T>>()
        pollTaskChannel.offer(future)
        return future
    }

    private fun launchPollObjectJob(): Job = launchGlobally(objectPoolDispatcher) {
        while (true) {
            val future = pollTaskChannel.receive()
            try {
                val pooledObject = getPooledObjectOrCreateNew()
                initPooledObject(pooledObject)
                future.complete(pooledObject)
            } catch (e: Exception) {
                log.error(e) { "poll object from pool exception." }
                future.completeExceptionally(e)
            }
        }
    }

    private fun initPooledObject(pooledObject: PooledObject<T>) {
        leakDetector.register(pooledObject) {
            try {
                pooledObject.leakCallback.accept(pooledObject)
            } catch (e: Exception) {
                log.error(e) { "The pooled object has leaked. object: $pooledObject ." }
            } finally {
                destroyPooledObject(pooledObject)
            }
        }
        pooledObject.released.set(false)
    }

    private suspend fun getPooledObjectOrCreateNew(): PooledObject<T> {
        return if (createdCount.get() < maxSize) {
            val pooledObject = objectFactory.createNew(this).await()
            createdCount.incrementAndGet()
            log.debug { "create a new object. $pooledObject" }
            pooledObject
        } else {
            getFromPool()
        }
    }

    private suspend fun getFromPool(): PooledObject<T> {
        val oldPooledObject = poolChannel.receive()
        size.decrementAndGet()
        return if (isValid(oldPooledObject)) {
            log.debug { "get an old object. $oldPooledObject" }
            oldPooledObject
        } else {
            destroyPooledObject(oldPooledObject)
            getPooledObjectOrCreateNew()
        }
    }

    private fun destroyPooledObject(pooledObject: PooledObject<T>) {
        try {
            dispose.destroy(pooledObject)
        } catch (e: Exception) {
            log.error(e) { "destroy pooled object exception." }
        } finally {
            log.debug { "destroy the object: $pooledObject ." }
            Atomics.getAndDecrement(createdCount, 0)
        }
    }


    // release task
    private fun launchReleaseObjectJob(): Job = launchGlobally(objectPoolDispatcher) {
        while (true) {
            val pooledObject = releaseTaskChannel.receive()
            try {
                if (pooledObject.released.compareAndSet(false, true)) {
                    leakDetector.clear(pooledObject)
                    withTimeout(Duration.ofSeconds(timeout)) {
                        poolChannel.send(pooledObject)
                        size.incrementAndGet()
                    }
                    log.debug { "release pooled object: $pooledObject, pool size: ${size()}." }
                }
            } catch (e: Exception) {
                log.error(e) { "release pooled object exception" }
                destroyPooledObject(pooledObject)
            }
        }
    }

    override fun release(pooledObject: PooledObject<T>) {
        releaseTaskChannel.offer(pooledObject)
    }

    override fun isValid(pooledObject: PooledObject<T>): Boolean {
        return validator.isValid(pooledObject)
    }

    override fun size(): Int = size.get()

    override fun isEmpty(): Boolean {
        return size() == 0
    }

    override fun getCoroutineDispatcher(): CoroutineDispatcher = objectPoolDispatcher

    override fun getLeakDetector(): FixedTimeLeakDetector<PooledObject<T>> = leakDetector

    override fun getCreatedObjectCount(): Int = createdCount.get()

    override fun init() {
    }

    override fun destroy() {
        while (true) {
            val o = poolChannel.poll()
            if (o == null) {
                break
            } else {
                dispose.destroy(o)
            }
        }

        leakDetector.stop()
        poolChannel.close()
        pollObjectJob.cancel()
        releaseObjectJob.cancel()
    }
}