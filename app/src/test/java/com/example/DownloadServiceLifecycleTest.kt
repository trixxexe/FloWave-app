package com.example

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DownloadServiceLifecycleTest {

    /**
     * Simulates the exact synchronization and lifecycle logic implemented in FloWaveDownloadService.
     */
    private class DownloadCoordinator {
        val lock = Any()
        val activeTasks = mutableMapOf<String, Job>()
        @Volatile var latestStartId = 0
        val stoppedWithStartId = AtomicInteger(-1)
        val stopForegroundCalled = AtomicBoolean(false)
        val taskStatus = ConcurrentHashMap<String, String>()

        fun onStartCommand(url: String?, startId: Int, scope: CoroutineScope, taskDeferred: CompletableDeferred<Unit>): Boolean {
            synchronized(lock) {
                latestStartId = startId
            }

            if (url.isNullOrBlank() || !url.startsWith("http")) {
                synchronized(lock) {
                    if (activeTasks.isEmpty()) {
                        stopForegroundCalled.set(true)
                        stoppedWithStartId.set(startId)
                    }
                }
                return false
            }

            val taskId = "task_$startId"
            taskStatus[taskId] = "DOWNLOADING"

            val job = scope.launch {
                try {
                    taskDeferred.await()
                    taskStatus[taskId] = "DONE"
                } catch (cancelled: CancellationException) {
                    taskStatus[taskId] = "CANCELLED"
                    throw cancelled
                } catch (error: Exception) {
                    taskStatus[taskId] = "FAILED"
                } finally {
                    synchronized(lock) {
                        activeTasks.remove(taskId)
                        if (activeTasks.isEmpty()) {
                            stopForegroundCalled.set(true)
                            stoppedWithStartId.set(latestStartId)
                        }
                    }
                }
            }

            synchronized(lock) {
                activeTasks[taskId] = job
            }
            return true
        }
    }

    @Test
    fun `concurrent downloads do not stop service until all work is completed`() = runBlocking {
        val coordinator = DownloadCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + Job())

        val task1Deferred = CompletableDeferred<Unit>()
        val task2Deferred = CompletableDeferred<Unit>()

        // Start task 1 with startId = 1
        assertTrue(coordinator.onStartCommand("https://example.com/audio1.mp3", 1, scope, task1Deferred))
        assertEquals(1, coordinator.latestStartId)
        assertEquals(1, coordinator.activeTasks.size)

        // Start task 2 with startId = 2
        assertTrue(coordinator.onStartCommand("https://example.com/audio2.mp3", 2, scope, task2Deferred))
        assertEquals(2, coordinator.latestStartId)
        assertEquals(2, coordinator.activeTasks.size)

        val job1 = coordinator.activeTasks["task_1"]!!
        val job2 = coordinator.activeTasks["task_2"]!!

        // Complete task 1 first
        task1Deferred.complete(Unit)
        job1.join()

        // Service should NOT have stopped because task 2 is still active
        assertFalse(coordinator.activeTasks.containsKey("task_1"))
        assertTrue(coordinator.activeTasks.containsKey("task_2"))
        assertFalse(coordinator.stopForegroundCalled.get())
        assertEquals(-1, coordinator.stoppedWithStartId.get())

        // Now complete task 2
        task2Deferred.complete(Unit)
        job2.join()

        // Now all tasks are done; service should stop with latestStartId (2)
        assertTrue(coordinator.activeTasks.isEmpty())
        assertTrue(coordinator.stopForegroundCalled.get())
        assertEquals(2, coordinator.stoppedWithStartId.get())
        assertEquals("DONE", coordinator.taskStatus["task_1"])
        assertEquals("DONE", coordinator.taskStatus["task_2"])
    }

    @Test
    fun `earlier startId completing last stops with latestStartId`() = runBlocking {
        val coordinator = DownloadCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + Job())

        val task1Deferred = CompletableDeferred<Unit>()
        val task2Deferred = CompletableDeferred<Unit>()

        coordinator.onStartCommand("https://example.com/1", 1, scope, task1Deferred)
        coordinator.onStartCommand("https://example.com/2", 2, scope, task2Deferred)

        val job1 = coordinator.activeTasks["task_1"]!!
        val job2 = coordinator.activeTasks["task_2"]!!

        // Task 2 finishes BEFORE Task 1
        task2Deferred.complete(Unit)
        job2.join()

        // Task 1 still running; service must not stop
        assertFalse(coordinator.stopForegroundCalled.get())
        assertEquals(-1, coordinator.stoppedWithStartId.get())

        // Now Task 1 finishes
        task1Deferred.complete(Unit)
        job1.join()

        // Must stop with latestStartId (2), NOT task 1's startId (1)
        assertTrue(coordinator.stopForegroundCalled.get())
        assertEquals(2, coordinator.stoppedWithStartId.get())
    }

    @Test
    fun `invalid URL does not stop service if other downloads are in flight`() = runBlocking {
        val coordinator = DownloadCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + Job())

        val task1Deferred = CompletableDeferred<Unit>()
        coordinator.onStartCommand("https://example.com/valid", 1, scope, task1Deferred)

        // Send invalid URL with startId = 2 while task 1 is active
        assertFalse(coordinator.onStartCommand("not_a_valid_url", 2, scope, CompletableDeferred()))

        // Task 1 is still running; service should NOT be stopped
        assertFalse(coordinator.stopForegroundCalled.get())
        assertEquals(1, coordinator.activeTasks.size)

        val job1 = coordinator.activeTasks["task_1"]!!
        task1Deferred.complete(Unit)
        job1.join()

        assertTrue(coordinator.stopForegroundCalled.get())
        assertEquals(2, coordinator.stoppedWithStartId.get())
    }

    @Test
    fun `cancellation marks task cancelled and cleans up activeTasks`() = runBlocking {
        val coordinator = DownloadCoordinator()
        val scope = CoroutineScope(Dispatchers.Default + Job())

        val taskDeferred = CompletableDeferred<Unit>()
        coordinator.onStartCommand("https://example.com/audio", 1, scope, taskDeferred)

        val taskJob = coordinator.activeTasks["task_1"]!!
        taskJob.cancelAndJoin() // Deterministically cancel and wait for cleanup

        assertTrue(coordinator.activeTasks.isEmpty())
        assertEquals("CANCELLED", coordinator.taskStatus["task_1"])
        assertTrue(coordinator.stopForegroundCalled.get())
        assertEquals(1, coordinator.stoppedWithStartId.get())
    }
}
