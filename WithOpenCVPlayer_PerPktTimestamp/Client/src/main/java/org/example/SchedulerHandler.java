package org.example;

import net.openhft.affinity.Affinity;
import net.openhft.affinity.AffinityLock;

import java.io.IOException;
import java.net.*;

class RTTHandler implements Runnable {
    String serverIp;
    int serverPort;
    int timeout;
    int id;
    int recvBufSize;

    RTTHandler(String serverIp, int serverPort, int timeout, int id) throws IOException {
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.timeout = timeout;
        this.id = id;
    }

    public void connect() throws SocketException, UnknownHostException {
        // Send a "Hi" message to the server to create a separate thread for the client
        DatagramSocket socket = new DatagramSocket();
        InetAddress ip_server = InetAddress.getByName(this.serverIp);

        recvBufSize = 20;
        byte[] hiBuf = "Hi".getBytes();
        byte[] recvBuf = new byte[recvBufSize];
        DatagramPacket dpSend = new DatagramPacket(hiBuf, hiBuf.length, ip_server, this.serverPort);
        DatagramPacket dpReceive;
        boolean doneHandshake = true;
        try {
            while(doneHandshake){
                socket.send(dpSend);
//                System.out.println(this.id + " Hi Sent");

                dpReceive = new DatagramPacket(recvBuf, recvBuf.length);
                socket.setSoTimeout(this.timeout);
                try{
                    socket.receive(dpReceive);
                    doneHandshake = false;
                    recvBufSize = dpReceive.getLength();
//                    System.out.println(this.id + " received bytes " + dpReceive.getLength() + " " + new String(recvBuf) + " Received HiAck");
//                    System.out.println(this.id + " Received HiAck");
                } catch (SocketTimeoutException e){
//                    System.out.println(this.id + " Resending Hi");
                }
            }
        } catch (
                IOException e) {
            throw new RuntimeException(e);
        }

        // Receiving timestamps for RTT estimation
        while (true) {
            recvBuf = new byte[recvBufSize];
            dpReceive = new DatagramPacket(recvBuf, recvBuf.length);
            socket.setSoTimeout(0);

            try {
                socket.receive(dpReceive);
                long recvTimestamp = Long.parseLong(new String(recvBuf));
//                System.out.println(this.id + " Received timestamp: " + recvTimestamp);

                dpSend = new DatagramPacket(recvBuf, recvBuf.length, ip_server, this.serverPort);
                socket.send(dpSend);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void run() {
//        AffinityLock al = AffinityLock.acquireLockLastMinus(1);
//        System.out.println( this.id + " CPU Id for RTT thread: " + Affinity.getCpu() + " Thread ID: " + Affinity.getThreadId());
//        try {
            try {
                connect();
            } catch (SocketException e) {
                throw new RuntimeException(e);
            } catch (UnknownHostException e) {
                throw new RuntimeException(e);
            }
//        } finally {
//            al.release();
//        }
    }
}


class ControlChannelHandler{
    String serverIp;
    int serverPort;
    int id;
    DatagramSocket socket;

    ControlChannelHandler(String serverIp, int serverPort, int id) throws IOException {
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.id = id;
        socket = new DatagramSocket();
    }

    public void sendStats(String stats) throws IOException {
        byte[] buf = stats.getBytes();
        DatagramPacket dpSend = new DatagramPacket(buf, buf.length, InetAddress.getByName(this.serverIp), this.serverPort);
        socket.send(dpSend);
//        System.out.println(this.id + " Stats sent: " + stats);
    }
}
