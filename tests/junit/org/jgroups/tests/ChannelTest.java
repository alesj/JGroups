
package org.jgroups.tests;


import org.jgroups.*;
import org.jgroups.util.Util;
import org.testng.annotations.Test;

import java.util.LinkedList;
import java.util.List;


/**
 * Tests various methods in JChannel
 * @author Bela Ban
 */
@Test(groups=Global.STACK_DEPENDENT,sequential=false)
public class ChannelTest extends ChannelTestBase {       

    @Test
    public void testBasicOperations() throws Exception {
        JChannel c1 = createChannel(true,1);
        JChannel c2=null;

        try {
            c1.connect("testBasicOperations");
            assert c1.isOpen();
            assert c1.isConnected();
       
            assert c1.getAddress() != null;
            assert c1.getView() != null;
            assert c1.getView().getMembers().contains(c1.getAddress());
       
            c1.connect("testBasicOperations");
            c1.disconnect();
            assert c1.isConnected() == false;
            assert c1.isOpen();
            assert c1.getAddress() == null;
            assert c1.getView() == null;
            assert c1.getClusterName() == null;
       
            c1.connect("testBasicOperations");

            c1.close();
       
            try {
                c1.connect("testBasicOperations");
                throw new IllegalStateException("Should generated exception, and it has NOT");
            }
            catch (Exception e) {
                assert e instanceof ChannelClosedException;
            }
       
            assert c1.isConnected() == false;
            assert c1.isOpen() == false;
            assert c1.getAddress() == null;
            assert c1.getView() == null;
       
            assert c1.getClusterName() == null;
       
            c1 = createChannel(true,2);
            c1.connect("testBasicOperations");
            c2 = createChannel(c1);
            c2.connect("testBasicOperations");
       
            Util.sleep(1000);
       
            assert c1.isOpen();
            assert c1.isConnected();
       
            assert c1.getAddress() != null;
            assert c1.getView() != null;
            assert c1.getView().getMembers().contains(c1.getAddress());
            assert c1.getView().getMembers().contains(c2.getAddress());
       
            assert c2.isOpen();
            assert c2.isConnected();
       
            assert c2.getAddress() != null;
            assert c2.getView() != null;
            assert c2.getView().getMembers().contains(c2.getAddress());
            assert c2.getView().getMembers().contains(c1.getAddress());
       
            c2.close();
            Util.sleep(1000);
       
            assert c2.isOpen() == false;
            assert c2.isConnected() == false;
       
            assert c2.getAddress() == null;
            assert c2.getView() == null;
       
            assert c1.isOpen();
            assert c1.isConnected();
       
            assert c1.getAddress() != null;
            assert c1.getView() != null;
            assert c1.getView().getMembers().contains(c1.getAddress());
            assert c1.getView().getMembers().contains(c2.getAddress()) == false;
        }
        finally {
            Util.close(c1,c2);
        }
    }  

    @Test
    public void testViewChange() throws Exception {
        JChannel ch1 = createChannel(true,2);
        ViewChecker checker=new ViewChecker(ch1);
        ch1.setReceiver(checker);
        ch1.connect("testViewChange");

        Channel ch2=createChannel(ch1);
        try {
            ch2.connect("testViewChange");
            assertTrue(checker.getReason(), checker.isSuccess());
            ch2.close();
            assertTrue(checker.getReason(), checker.isSuccess());
        }
        finally {
            Util.close(ch1,ch2);
        }
    }


    @Test
    public void testIsConnectedOnFirstViewChange() throws Exception {
        JChannel ch1 = createChannel(true,2);        
        Channel ch2=createChannel(ch1);
        ConnectedChecker tmp=new ConnectedChecker(ch2);
        ch2.setReceiver(tmp);
        try {
            ch1.connect("testIsConnectedOnFirstViewChange");
            ch2.connect("testIsConnectedOnFirstViewChange");
            assert tmp.isConnected();
        }
        finally {
            Util.close(ch1,ch2);
        }
    }



    @Test
    public void testNoViewIsReceivedAfterDisconnect() throws Exception {
        JChannel ch1 = createChannel(true,2);        
        Channel ch2=createChannel(ch1);
        MyViewChecker ra = new MyViewChecker(ch2);
        ch2.setReceiver(ra);

        try {
            ch1.connect("testNoViewIsReceivedAferDisconnect");
            ch2.connect("testNoViewIsReceivedAferDisconnect");
            Util.sleep(500);
            ch2.disconnect();
            Util.sleep(1000);
            assert !ra.receivedViewWhenDisconnected : "Received view where not member";
        }
        finally {
            Util.close(ch1,ch2);
        }
    }
    
    @Test
    public void testNoViewIsReceivedAfterClose() throws Exception {
        JChannel ch1 = createChannel(true,2);        
        Channel ch2=createChannel(ch1);
        MyViewChecker ra = new MyViewChecker(ch2);
        ch2.setReceiver(ra);

        try {
            ch1.connect("testNoViewIsReceivedAferClose");
            ch2.connect("testNoViewIsReceivedAferClose");
            Util.sleep(200);
            ch2.close();
            Util.sleep(1000);
            assert !ra.receivedViewWhenDisconnected : "Received view where not member";
        }
        finally {
            Util.close(ch1,ch2);
        }
    }


    @Test(expectedExceptions={NullPointerException.class})
    public void testNullMessage() throws Exception {
        JChannel ch1 = createChannel(true,2);        
        try{
            ch1.connect("testNullMessage");
            ch1.send(null);
        }
        finally{
            Util.close(ch1);
        }             
    }

    @Test
    public void testOrdering() throws Exception {
        final int NUM=100;
        JChannel ch=createChannel(true, 2);
        MyReceiver receiver=new MyReceiver(NUM);
        ch.setReceiver(receiver);
        try {
            ch.connect("testOrdering");
            for(int i=1;i <= NUM;i++) {
                ch.send(new Message(null, null, new Integer(i)));
                // System.out.println("-- sent " + i);
            }
            receiver.waitForCompletion();
            List<Integer> nums=receiver.getNums();
            checkMonotonicallyIncreasingNumbers(nums);
        }
        finally {
            Util.close(ch);
        }
    }

    private static void checkMonotonicallyIncreasingNumbers(List<Integer> nums) {
        int current=-1;
        for(int num: nums) {
            if(current < 0) {
                current=num;
            }
            else {
                assert ++current == num : "list is " + nums;
            }
        }
    }


    private static class MyReceiver extends ReceiverAdapter {
        final List<Integer> nums=new LinkedList<Integer>();
        final int expected;

        public MyReceiver(int expected) {
            this.expected=expected;
        }

        public List<Integer> getNums() {
            return nums;
        }

        public void waitForCompletion() throws InterruptedException {
            synchronized(nums) {
                while(nums.size() < expected) {
                    nums.wait();
                }
            }
        }

        public void receive(Message msg) {
            Integer num=(Integer)msg.getObject();
            synchronized(nums) {
                nums.add(num);
                if(nums.size() >= expected) {
                    nums.notifyAll();
                }
            }
        }
    }


    private static class ConnectedChecker extends ReceiverAdapter {
        boolean connected=false;

        public ConnectedChecker(Channel channel) {
            this.channel=channel;
        }

        final Channel channel;

        public boolean isConnected() {
            return connected;
        }

        public void viewAccepted(View new_view) {
            connected=channel.isConnected();
            // System.out.println("ConnectedChecker: channel.isConnected()=" + connected + ", view=" + new_view);
        }
    }

    private static class ViewChecker extends ReceiverAdapter {
        final Channel channel;
        boolean success=true;
        String reason="";

        public ViewChecker(Channel channel) {
            this.channel=channel;
        }

        public String getReason() {
            return reason;
        }

        public boolean isSuccess() {
            return success;
        }

        public void viewAccepted(View new_view) {
            View view=channel.getView();
            String str="viewAccepted(): channel's view=" + view + "\nreceived view=" + new_view;
            // System.out.println(str);           
            if(view!= null && !view.equals(new_view)) {
                success=false;
                reason+=str + "\n";
            }
        }
    }


    private static class MyViewChecker extends ReceiverAdapter {
        private boolean receivedViewWhenDisconnected;
        private final Channel ch;

        public MyViewChecker(Channel ch) {
            this.ch=ch;
        }

        public void viewAccepted(View new_view) {
            receivedViewWhenDisconnected = !new_view.containsMember(ch.getAddress());
        }

    }



}


