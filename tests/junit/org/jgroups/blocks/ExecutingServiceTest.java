package org.jgroups.blocks;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;

import org.jgroups.Address;
import org.jgroups.Global;
import org.jgroups.JChannel;
import org.jgroups.blocks.executor.ExecutionCompletionService;
import org.jgroups.blocks.executor.ExecutionRunner;
import org.jgroups.blocks.executor.ExecutionService;
import org.jgroups.blocks.executor.ExecutionService.DistributedFuture;
import org.jgroups.blocks.executor.Executions;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.protocols.CENTRAL_EXECUTOR;
import org.jgroups.protocols.Executing.Owner;
import org.jgroups.stack.Protocol;
import org.jgroups.stack.ProtocolStack;
import org.jgroups.tests.ChannelTestBase;
import org.jgroups.util.NotifyingFuture;
import org.jgroups.util.Streamable;
import org.jgroups.util.Util;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** Tests {@link org.jgroups.blocks.executor.ExecutionService}
 * @author wburns
 */
@Test(groups=Global.STACK_DEPENDENT,sequential=true)
public class ExecutingServiceTest extends ChannelTestBase {
    protected static Log log = LogFactory.getLog(ExecutingServiceTest.class);
    protected static AtomicReference<CyclicBarrier> requestBlocker = 
        new AtomicReference<CyclicBarrier>();
    
    protected JChannel c1, c2, c3;
    protected ExecutionService e1, e2, e3;
    protected ExecutionRunner er1, er2, er3;
    
    @BeforeClass
    protected void init() throws Exception {
        c1=createChannel(true, 3, "A");
        addExecutingProtocol(c1);
        er1=new ExecutionRunner(c1);
        c1.connect("ExecutionServiceTest");

        c2=createChannel(c1, "B");
        er2=new ExecutionRunner(c2);
        c2.connect("ExecutionServiceTest");

        c3=createChannel(c1, "C");
        er3=new ExecutionRunner(c3);
        c3.connect("ExecutionServiceTest");
        
        LogFactory.getLog(ExecutionRunner.class).setLevel("trace");
    }
    
    @AfterClass
    protected void cleanup() {
        Util.close(c3,c2,c1);
    }
    
    @BeforeMethod
    protected void createExecutors() {
        e1=new ExecutionService(c1);
        e2=new ExecutionService(c2);
        e3=new ExecutionService(c3);
        
        // Clear out the queue, in case if test doesn't clear it
        SleepingStreamableCallable.canceledThreads.clear();
        // Reset the barrier in case test failed on the barrier
        SleepingStreamableCallable.barrier.reset();
    }
    
    public static class ExposedExecutingProtocol extends CENTRAL_EXECUTOR {
        
        public ExposedExecutingProtocol() {
            // We use the same id as the CENTRAL_EXECUTOR
            id=ClassConfigurator.getProtocolId(CENTRAL_EXECUTOR.class);
        }
        
        // @see org.jgroups.protocols.Executing#sendRequest(org.jgroups.Address, org.jgroups.protocols.Executing.Type, long, java.lang.Object)
        @Override
        protected void sendRequest(Address dest, Type type, long requestId,
                                   Object object) {
            CyclicBarrier barrier = requestBlocker.get();
            if (barrier != null) {
                try {
                    barrier.await();
                }
                catch (InterruptedException e) {
                    assert false : "Exception while waiting: " + e.toString();
                }
                catch (BrokenBarrierException e) {
                    assert false : "Exception while waiting: " + e.toString();
                }
            }
            super.sendRequest(dest, type, requestId, object);
        }
        
        public Queue<Runnable> getAwaitingConsumerQueue() {
            return _awaitingConsumer;
        }
        
        public Queue<Owner> getRequestsFromCoordinator() {
            return _runRequests;
        }
        
        public Lock getLock() {
            return _consumerLock;
        }
    }

    /**
     * This class is to be used to test to make sure that when a non callable
     * is to be serialized that it works correctly.
     * <p>
     * This class provides a few constructors that shouldn't be used.  They
     * are just present to possibly poke holes in the constructor array offset.
     * @param <V> The type that the value can be returned as
     * @author wburns
     */
    protected static class SimpleCallable<V> implements Callable<V> {
        final V _object;
        
        // This constructor shouldn't be used
        public SimpleCallable(String noUse) {
            throw new UnsupportedOperationException();
        }
        
        // This constructor shouldn't be used
        public SimpleCallable(Integer noUse) {
            throw new UnsupportedOperationException();
        }
        
        public SimpleCallable(V object) {
            _object = object;
        }
        
        // This constructor shouldn't be used
        public SimpleCallable() {
            throw new UnsupportedOperationException();
        }

        @Override
        public V call() throws Exception {
            return _object;
        }
    }
    
    protected static class SleepingStreamableCallable implements Callable<Void>, Streamable {
        long millis;
        
        public static BlockingQueue<Thread> canceledThreads = new LinkedBlockingQueue<Thread>();
        public static CyclicBarrier barrier = new CyclicBarrier(2);
        
        public SleepingStreamableCallable() {
            
        }
        
        public SleepingStreamableCallable(long millis) {
            this.millis=millis;
        }

        @Override
        public void writeTo(DataOutput out) throws IOException {
            out.writeLong(millis);
        }

        @Override
        public void readFrom(DataInput in) throws IOException,
                IllegalAccessException, InstantiationException {
            millis = in.readLong();
        }

        @Override
        public Void call() throws Exception {
            barrier.await();
            try {
                Thread.sleep(millis);
            }
            catch (InterruptedException e) {
                Thread interruptedThread = Thread.currentThread();
                if (log.isTraceEnabled())
                    log.trace("Submitted cancelled thread - " + interruptedThread);
                canceledThreads.offer(interruptedThread);
            }
            return null;
        }
        
        // @see java.lang.Object#toString()
        @Override
        public String toString() {
            return "SleepingStreamableCallable [timeout=" + millis + "]";
        }
    }
    
    protected static class SimpleStreamableCallable<V> implements Callable<V>, Streamable {
        V _object;
        
        public SimpleStreamableCallable() {
            
        }

        public SimpleStreamableCallable(V object) {
            _object = object;
        }

        @Override
        public V call() throws Exception {
            return _object;
        }

        // @see java.lang.Object#toString()
        @Override
        public String toString() {
            return "SimpleSerializableCallable [value=" + _object + "]";
        }

        @Override
        public void writeTo(DataOutput out) throws IOException {
            try {
                Util.writeObject(_object, out);
            }
            catch (IOException e) {
                throw e;
            }
            catch (Exception e) {
                throw new IOException(e);
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public void readFrom(DataInput in) throws IOException,
                IllegalAccessException, InstantiationException {
            try {
                _object = (V)Util.readObject(in);
            }
            catch (IOException e) {
                throw e;
            }
            catch (Exception e) {
                throw new IOException(e);
            }
        }
    }
    
    @Test
    public void testSimpleSerializableCallableSubmit() 
            throws InterruptedException, ExecutionException, TimeoutException {
        Long value = Long.valueOf(100);
        Callable<Long> callable = new SimpleStreamableCallable<Long>(value);
        Thread consumer = new Thread(er2);
        consumer.start();
        NotifyingFuture<Long> future = e1.submit(callable);
        Long returnValue = future.get(10L, TimeUnit.SECONDS);
        // We try to stop the thread.
        consumer.interrupt();
        assert value == returnValue : "The value returned doesn't match";
        
        consumer.join(2000);
        assert !consumer.isAlive() : "Consumer did not stop correctly";
    }
    
    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    public void testSimpleSerializableCallableConcurrently() 
            throws InterruptedException, ExecutionException, TimeoutException {
        Thread[] consumers = {new Thread(er1), new Thread(er2), new Thread(er3)};
        
        for (Thread thread : consumers) {
            thread.start();
        }
        
        Random random = new Random();
        
        int count = 100;
        Future[] futures1 = new Future[count];
        Future[] futures2 = new Future[count];
        Future[] futures3 = new Future[count];
        StringBuilder builder = new StringBuilder("base");
        for (int i = 0; i < count; i++) {
            builder.append(random.nextInt(10));
            String value = builder.toString();
            futures1[i] = e1.submit(new SimpleStreamableCallable(value));
            futures2[i] = e2.submit(new SimpleStreamableCallable(value));
            futures3[i] = e3.submit(new SimpleStreamableCallable(value));
        }
        
        for (int i = 0; i < count; i++) {
            // All 3 of the futures should have returned the same value
            Object value = futures1[i].get(10L, TimeUnit.SECONDS);
            assert value.equals(futures2[i].get(10L, TimeUnit.SECONDS));
            assert value.equals(futures3[i].get(10L, TimeUnit.SECONDS));
            
            // Make sure that same value is what it should be
            CharSequence seq = builder.subSequence(0, 5+i);
            assert value.equals(seq);
        }
        
        for (Thread consumer : consumers) {
            // We try to stop the thread.
            consumer.interrupt();
            
            consumer.join(2000);
            assert !consumer.isAlive() : "Consumer did not stop correctly";
        }
    }
    
    /**
     * Interrupts can have a lot of timing issues, so we run it a lot to make
     * sure we find all the issues.
     * @throws InterruptedException
     * @throws BrokenBarrierException
     * @throws TimeoutException
     */
    @Test
    public void testInterruptWhileRunningAlot() throws InterruptedException, BrokenBarrierException, TimeoutException {
        for (int i = 0; i < 500; ++i)
            testInterruptTaskRequestWhileRunning();
    }
    
    protected void testInterruptTaskRequestWhileRunning() 
            throws InterruptedException, BrokenBarrierException, TimeoutException {
        Callable<Void> callable = new SleepingStreamableCallable(10000);
        Thread consumer = new Thread(er2);
        consumer.start();
        NotifyingFuture<Void> future = e1.submit(callable);
        
        // We wait until it is ready
        SleepingStreamableCallable.barrier.await(5, TimeUnit.SECONDS);
        if (log.isTraceEnabled())
            log.trace("Cancelling future by interrupting");
        future.cancel(true);
        
        Thread cancelled = SleepingStreamableCallable.canceledThreads.poll(2, 
            TimeUnit.SECONDS);

        if (log.isTraceEnabled())
            log.trace("Cancelling task by interrupting");
        // We try to stop the thread now which should now stop the runner
        consumer.interrupt();
        assert cancelled != null : "There was no cancelled thread";
        
        consumer.join(2000);
        assert !consumer.isAlive() : "Consumer did not stop correctly";
    }
    
    @Test
    public void testInterruptTaskRequestBeforeRunning() 
            throws InterruptedException, TimeoutException {
        Callable<Void> callable = new SleepingStreamableCallable(10000);
        NotifyingFuture<Void> future = e1.submit(callable);
        
        // Now we make sure that 
        ExposedExecutingProtocol protocol = 
            (ExposedExecutingProtocol)c1.getProtocolStack().findProtocol(
                ExposedExecutingProtocol.class);
        Queue<Runnable> queue = protocol.getAwaitingConsumerQueue();
        Lock lock = protocol.getLock();
        
        lock.lock();
        try {
            assert queue.peek() != null : "The object in queue doesn't match";
        }
        finally {
            lock.unlock();
        }
        // This should remove the task before it starts, since the consumer is
        // not yet running
        future.cancel(false);
        
        lock.lock();
        try {
            assert queue.peek() == null : "There should be no more objects in the queue";
        }
        finally {
            lock.unlock();
        }
    }
    
    @Test
    public void testExecutorAwaitTerminationNoInterrupt() throws InterruptedException, 
            BrokenBarrierException, TimeoutException {
        testExecutorAwaitTermination(false);
    }
    
    @Test
    public void testExecutorAwaitTerminationInterrupt() throws InterruptedException, 
            BrokenBarrierException, TimeoutException {
        testExecutorAwaitTermination(true);
    }
    
    protected void testExecutorAwaitTermination(boolean interrupt) 
            throws InterruptedException, BrokenBarrierException, TimeoutException {
        Thread consumer = new Thread(er2);
        consumer.start();
        // We send a task that waits for 101 milliseconds and then finishes
        Callable<Void> callable = new SleepingStreamableCallable(101);
        e1.submit(callable);
        
        // We wait for the thread to start
        SleepingStreamableCallable.barrier.await(2, TimeUnit.SECONDS);
        
        if (interrupt) {
            if (log.isTraceEnabled())
                log.trace("Cancelling futures by interrupting");
            e1.shutdownNow();
            // We wait for the task to be interrupted.
            assert SleepingStreamableCallable.canceledThreads.poll(2, 
                TimeUnit.SECONDS) != null : 
                    "Thread wasn't interrupted due to our request";
        }
        else {
            e1.shutdown();
        }
        
        assert e1.awaitTermination(2, TimeUnit.SECONDS) : "Executor didn't terminate fast enough";
        
        try {
            e1.submit(callable);
            assert false : "Task was submitted, where as it should have been rejected";
        }
        catch (RejectedExecutionException e) {
            // We should have received this exception
        }
        
        if (log.isTraceEnabled())
            log.trace("Cancelling task by interrupting");
        // We try to stop the thread.
        consumer.interrupt();
        
        consumer.join(2000);
        assert !consumer.isAlive() : "Consumer did not stop correctly";
    }
    
    @Test
    public void testNonSerializableCallable() throws SecurityException, 
            NoSuchMethodException, InterruptedException, ExecutionException, 
            TimeoutException {
        Thread consumer = new Thread(er2);
        consumer.start();
        
        Long value = Long.valueOf(100);
        
        @SuppressWarnings("rawtypes")
        Constructor<SimpleCallable> constructor = 
            SimpleCallable.class.getConstructor(Object.class);
        constructor.getGenericParameterTypes();
        @SuppressWarnings("unchecked")
        Callable<Long> callable = (Callable<Long>)Executions.serializableCallable(
            constructor, value);
        
        NotifyingFuture<Long> future = e1.submit(callable);
        Long returnValue = future.get(10L, TimeUnit.SECONDS);
        // We try to stop the thread.
        consumer.interrupt();
        assert value == returnValue : "The value returned doesn't match";
        
        consumer.join(2000);
        assert !consumer.isAlive() : "Consumer did not stop correctly";
    }
    
    @Test
    public void testExecutionCompletionService() throws InterruptedException {
        Thread consumer1 = new Thread(er2);
        consumer1.start();
        Thread consumer2 = new Thread(er3);
        consumer2.start();
        
        ExecutionCompletionService<Void> service = new ExecutionCompletionService<Void>(e1);
        
        // The sleeps will not occur until both threads get there due to barrier
        // This should result in future2 always ending first since the sleep
        // is 3 times smaller
        Future<Void> future1 = service.submit(new SleepingStreamableCallable(300));
        Future<Void> future2 = service.submit(new SleepingStreamableCallable(100));
        
        assert service.poll(2, TimeUnit.SECONDS) == future2 : "The task either didn't come back or was in wrong order";
        assert service.poll(2, TimeUnit.SECONDS) == future1 : "The task either didn't come back or was in wrong order";
        
        // We try to stop the threads.
        consumer1.interrupt();
        consumer2.interrupt();
        
        consumer1.join(2000);
        assert !consumer1.isAlive() : "Consumer did not stop correctly";
        consumer2.join(2000);
        assert !consumer2.isAlive() : "Consumer did not stop correctly";
    }
    
    @Test
    public void testCoordinatorWentDownWhileSendingMessage() throws Exception {
        // It is 3 calls.
        // The first is the original message sending to the coordinator
        // The second is the new message to send the request to the new coordinator
        // The last is our main method below waiting for others
        final CyclicBarrier barrier = new CyclicBarrier(3);
        
        requestBlocker.set(barrier);
        
        final Callable<Integer> callable = new SimpleStreamableCallable<Integer>(23);
        ExecutorService service = Executors.newCachedThreadPool();
        service.submit(new Runnable() {

            @Override
            public void run() {
                e2.submit(callable);
            }
        });
        
        service.submit(new Runnable() {
            @Override
            public void run() {
                // We close the coordinator
                Util.close(c1);
            }
        });
        
        barrier.await(2, TimeUnit.SECONDS);
        
        requestBlocker.getAndSet(null).reset();
        
        // We need to reconnect the channel now
        c1=createChannel(c2, "A");
        addExecutingProtocol(c1);
        er1=new ExecutionRunner(c1);
        c1.connect("ExecutionServiceTest");
        
        service.shutdown();
        service.awaitTermination(2, TimeUnit.SECONDS);
        
        // Now we make sure that the new coordinator has the requests
        ExposedExecutingProtocol protocol = 
            (ExposedExecutingProtocol)c2.getProtocolStack().findProtocol(
                ExposedExecutingProtocol.class);
        Queue<Runnable> runnables = protocol.getAwaitingConsumerQueue();
        
        assert runnables.size() == 1 : "There is no runnable in the queue";
        Runnable task = runnables.iterator().next();
        assert task instanceof DistributedFuture : "The task wasn't a distributed future like we thought";
        assert callable == ((DistributedFuture<?>)task).getCallable() : "The inner callable wasn't the same";
        
        Queue<Owner> requests = protocol.getRequestsFromCoordinator();
        assert requests.size() == 1 : "There is no request in the coordinator queue - " + requests.size();
        Owner owner = requests.iterator().next();
        assert owner.getAddress().equals(c2.getAddress()) : "The request Address doesn't match";
        assert owner.getRequestId() == 0 : "We only had 1 request so it should be zero still";
    }
    
    @Test
    public void testInvokeAnyCalls() throws InterruptedException, ExecutionException {
        Thread consumer1 = new Thread(er2);
        consumer1.start();
        Thread consumer2 = new Thread(er3);
        consumer2.start();
        
        Collection<Callable<Long>> callables = new ArrayList<Callable<Long>>();
        
        callables.add(new SimpleStreamableCallable<Long>((long)10));
        callables.add(new SimpleStreamableCallable<Long>((long)100));
        Long value = e1.invokeAny(callables);
        
        assert value == 10 || value == 100 : "The task didn't return the right value";
        
        // We try to stop the threads.
        consumer1.interrupt();
        consumer2.interrupt();
        
        consumer1.join(2000);
        assert !consumer1.isAlive() : "Consumer did not stop correctly";
        consumer2.join(2000);
        assert !consumer2.isAlive() : "Consumer did not stop correctly";
    }
    
    protected void addExecutingProtocol(JChannel ch) {
        ProtocolStack stack=ch.getProtocolStack();
        Protocol protocol = new ExposedExecutingProtocol();
        protocol.setLevel("trace");
        stack.insertProtocolAtTop(protocol);
    }
}
