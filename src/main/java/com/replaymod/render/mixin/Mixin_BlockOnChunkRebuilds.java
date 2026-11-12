package com.replaymod.render.mixin;

//#if MC>=11500
import com.mojang.blaze3d.systems.RenderSystem;
import com.replaymod.render.hooks.ForceChunkLoadingHook;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.util.thread.TaskScheduler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

//#if MC>=12106
import net.minecraft.client.renderer.chunk.SectionMesh;
import org.spongepowered.asm.mixin.Mutable;
import java.util.concurrent.Executor;
//#endif

//#if MC>=12105
import org.spongepowered.asm.mixin.Mutable;
import java.util.AbstractQueue;
import java.util.Iterator;
//#endif

//#if MC>=12102
import net.minecraft.util.thread.ConsecutiveExecutor;
//#endif

//#if MC>=12003
import net.minecraft.client.renderer.SectionBufferBuilderPool;
//#endif

@Mixin(SectionRenderDispatcher.class)
public abstract class Mixin_BlockOnChunkRebuilds implements ForceChunkLoadingHook.IBlockOnChunkRebuilds {
    //#if MC>=12003
    @Shadow @Final private SectionBufferBuilderPool bufferPool;
    @Unique
    private int getAvailableBufferCount() {
        return this.bufferPool.getFreeBufferCount();
    }
    //#else
    //$$ @Shadow @Final private Queue<BlockBufferBuilderStorage> threadBuffers;
    //$$ @Unique
    //$$ private int getAvailableBufferCount() {
    //$$     return threadBuffers.size();
    //$$ }
    //#endif

    //#if MC>=11800
    @Unique
    private boolean upload() {
        boolean anything = false;
        Runnable runnable;
        while ((runnable = this.toUpload.poll()) != null) {
            runnable.run();
            anything = true;
        }
        //#if MC>=12106
        SectionMesh entry;
            while ((entry = this.toClose.poll()) != null) {
            entry.close();
            anything = true;
        }
        //#endif
        return anything;
    }
    //#else
    //$$ @Shadow public abstract boolean upload();
    //#endif

    //#if MC>=12102
    @Shadow @Final private ConsecutiveExecutor consecutiveExecutor;
    //#else
    //$$ @Shadow @Final private TaskExecutor<Runnable> mailbox;
    //#endif

    @Shadow protected abstract void runTask();

    //#if MC>=12105
    @Mutable
    //#endif
    @Shadow @Final private Queue<Runnable> toUpload;
    private final Lock waitingForWorkLock = new ReentrantLock();
    private final Condition newWork = waitingForWorkLock.newCondition();
    private volatile boolean allDone;

    private int totalBufferCount;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rememberTotalThreads(CallbackInfo ci) {
        this.totalBufferCount = getAvailableBufferCount();
    }

    @Inject(method = "runTask", at = @At("RETURN"))
    private void notifyMainThreadIfEverythingIsDone(CallbackInfo ci) {
        if (getAvailableBufferCount() == this.totalBufferCount) {
            // Looks like we're done, better notify the main thread in case the previous task didn't generate an upload
            this.waitingForWorkLock.lock();
            try {
                this.allDone = true;
                this.newWork.signalAll();
            } finally {
                this.waitingForWorkLock.unlock();
            }
        } else {
            this.allDone = false;
        }
    }

    //#if MC>=12106
    @Shadow @Final @Mutable
    Executor mainThreadUploadExecutor;
    @Shadow @Final @Mutable Queue<SectionMesh> toClose;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void notifyMainThreadOfNewUpload(CallbackInfo ci) {
        Executor innerUploadExecutor = this.mainThreadUploadExecutor;
        this.mainThreadUploadExecutor = runnable -> {
            innerUploadExecutor.execute(runnable);
            waitingForWorkLock.lock();
            try {
                newWork.signal();
            } finally {
                waitingForWorkLock.unlock();
            }
        };
        Queue<SectionMesh> innerRenderQueue = this.toClose;
        this.toClose = new AbstractQueue<>() {
            @Override
            public boolean offer(SectionMesh runnable) {
                boolean result = innerRenderQueue.offer(runnable);
                waitingForWorkLock.lock();
                try {
                    newWork.signal();
                } finally {
                    waitingForWorkLock.unlock();
                }
                return result;
            }
            @Override public SectionMesh poll() { return innerRenderQueue.poll(); }
            @Override public SectionMesh peek() { return innerRenderQueue.peek(); }
            @Override public int size() { return innerRenderQueue.size(); }
            @Override public Iterator<SectionMesh> iterator() { return innerRenderQueue.iterator(); }
        };
    }
    //#elseif MC>=12105
    //$$ @Inject(method = "<init>", at = @At("RETURN"))
    //$$ private void notifyMainThreadOfNewUpload(CallbackInfo ci) {
    //$$     Queue<Runnable> inner = this.uploadQueue;
    //$$     this.uploadQueue = new AbstractQueue<>() {
    //$$         @Override
    //$$         public boolean offer(Runnable runnable) {
    //$$             boolean result = inner.offer(runnable);
    //$$             waitingForWorkLock.lock();
    //$$             try {
    //$$                 newWork.signal();
    //$$             } finally {
    //$$                 waitingForWorkLock.unlock();
    //$$             }
    //$$             return result;
    //$$         }
    //$$         @Override public Runnable poll() { return inner.poll(); }
    //$$         @Override public Runnable peek() { return inner.peek(); }
    //$$         @Override public int size() { return inner.size(); }
    //$$         @Override public Iterator<Runnable> iterator() { return inner.iterator(); }
    //$$     };
    //$$ }
    //#else
    //$$ @Inject(method = "scheduleUpload", at = @At("RETURN"))
    //$$ private void notifyMainThreadOfNewUpload(CallbackInfoReturnable<CompletableFuture<Void>> ci) {
    //$$     this.waitingForWorkLock.lock();
    //$$     try {
    //$$         this.newWork.signal();
    //$$     } finally {
    //$$         this.waitingForWorkLock.unlock();
    //$$     }
    //$$ }
    //#endif

    private boolean waitForMainThreadWork() {
        //#if MC>=12102
        this.consecutiveExecutor.scheduleWithResult(future -> {
            runTask();
            future.complete(getAvailableBufferCount() == this.totalBufferCount);
        }).join();
        //#else
        //$$ boolean allDone = this.mailbox.<Boolean>ask(reply -> () -> {
        //$$     scheduleRunTasks();
        //$$     reply.send(getAvailableBufferCount() == this.totalBufferCount);
        //$$ }).join();
        //#endif

        if (allDone) {
            return true;
        } else {
            this.waitingForWorkLock.lock();
            try {
                while (true) {
                    //#if MC<11900
                    //$$ // Now, what is this call doing here you might be wondering. Well, from a quick look over everything
                    //$$ // it does not look like it would be required but have a **very** close look at [scheduleUpload]:
                    //$$ // It is not actually guaranteed to run the upload on the main thread, it just looks like it (and
                    //$$ // was probably supposed to do that) but in actuality, because it adds the first, empty future to
                    //$$ // the upload queue before attaching the thenCompose callback, that future can actually be completed
                    //$$ // by the main thread before thenCompose is called, resulting in thenCompose immediately calling
                    //$$ // its callback on the same (non-main) thread.
                    //$$ // Looking at how the upload behaves executed on a non-main thread, it eventually just enqueues
                    //$$ // itself in the RenderSystem's queue and returns a future for that.
                    //$$ // So, even though our [notifyMainThreadOfNewUpload] gets executed after all that, we would simply
                    //$$ // dead-lock ourselves here (since the upload queue is already empty), if we did never do this call
                    //$$ // to run the upload scheduled via this particular path of code execution.
                    //$$ RenderSystem.replayQueue();
                    //#endif

                    if (this.allDone) {
                        return true;
                    } else if (!this.toUpload.isEmpty()) {
                        return false;
                    } else {
                        this.newWork.awaitUninterruptibly();
                    }
                }
            } finally {
                this.waitingForWorkLock.unlock();
            }
        }
    }

    @Override
    public boolean uploadEverythingBlocking() {
        boolean anything = false;

        boolean allChunksBuilt;
        do {
            allChunksBuilt = waitForMainThreadWork();
            while (upload()) {
                anything = true;
            }
        } while (!allChunksBuilt);

        return anything;
    }
}
//#endif
