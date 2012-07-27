package com.bealetech.metrics.reporting;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
* 2012-07-27
*
* @author Michael Rose <michael@fullcontact.com>
*/
public class DefaultSocketProvider implements UDPSocketProvider {

    private final String host;
    private final int port;

    public DefaultSocketProvider(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public DatagramSocket get() throws Exception {
        return new DatagramSocket(new InetSocketAddress(this.host, this.port));
    }

    @Override
    public DatagramPacket newPacket(ByteArrayOutputStream out) {
        byte[] dataBuffer;
        if (out != null) {
            dataBuffer = out.toByteArray();
        }
        else {
            dataBuffer = new byte[8192];
        }
        return new DatagramPacket(dataBuffer, dataBuffer.length);
    }
}
