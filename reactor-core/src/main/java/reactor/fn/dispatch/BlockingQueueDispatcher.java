/*
 * Copyright (c) 2011-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.fn.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.fn.cache.Cache;
import reactor.fn.cache.LoadingCache;
import reactor.fn.Supplier;
import reactor.support.QueueFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementation of {@link Dispatcher} that uses a {@link BlockingQueue} to queue tasks to be executed.
 *
 * @author Jon Brisbin
 * @author Stephane Maldini
 * @author Andy Wilkinson
 */
@SuppressWarnings("rawtypes")
public final class BlockingQueueDispatcher extends AbstractDispatcher {

	private static final AtomicInteger INSTANCE_COUNT = new AtomicInteger();

	private final ThreadGroup         threadGroup = new ThreadGroup("eventloop");
	private final BlockingQueue<Task> taskQueue   = QueueFactory.createQueue();
	private final Cache<Task> readyTasks;
	private final Thread      taskExecutor;

	/**
	 * Creates a new {@literal BlockingQueueDispatcher} with the given {@literal name} and {@literal backlog}.
	 *
	 * @param name    The name
	 * @param backlog The backlog size
	 */
	public BlockingQueueDispatcher(String name, int backlog) {
		this.readyTasks = new LoadingCache<Task>(
				new Supplier<Task>() {
					@Override
					public Task get() {
						return new BlockingQueueTask();
					}
				},
				backlog,
				200l
		);
		String threadName = name + "-dispatcher-" + INSTANCE_COUNT.incrementAndGet();

		this.taskExecutor = new Thread(threadGroup, new TaskExecutingRunnable(), threadName);
		this.taskExecutor.setDaemon(true);
		this.taskExecutor.setPriority(Thread.NORM_PRIORITY);
		this.taskExecutor.start();
	}

	@Override
	public boolean shutdown() {
		taskExecutor.interrupt();
		return super.shutdown();
	}

	@Override
	public boolean halt() {
		taskExecutor.interrupt();
		return super.halt();
	}

	@SuppressWarnings("unchecked")
	@Override
	protected <T> Task<T> createTask() {
		Task t = readyTasks.allocate();
		return (null != t ? t : new BlockingQueueTask());
	}

	private class BlockingQueueTask<T> extends Task<T> {

		@Override
		public void submit() {
			taskQueue.add(this);
		}
	}

	private class TaskExecutingRunnable implements Runnable {
		@SuppressWarnings("rawtypes")
		@Override
		public void run() {
			Task t = null;
			for (; ; ) {
				try {
					t = taskQueue.poll(200, TimeUnit.MILLISECONDS);
					if (null != t) {
						t.execute(getInvoker());
						decrementTaskCount();
					}
				} catch (InterruptedException e) {
					break;
				} catch (Exception e) {
					Logger log = LoggerFactory.getLogger(BlockingQueueDispatcher.class);
					if (log.isErrorEnabled()) {
						log.error(e.getMessage(), e);
					}
				} finally {
					if (null != t) {
						t.reset();
						readyTasks.deallocate(t);
					}
				}
			}
			Thread.currentThread().interrupt();
		}
	}

}
