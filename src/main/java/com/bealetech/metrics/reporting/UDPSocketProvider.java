package com.bealetech.metrics.reporting;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

/**
* 2012-07-27
*
* @author Michael Rose <michael@fullcontact.com>
*/
public interface UDPSocketProvider {
    DatagramSocket get() throws Exception;
    DatagramPacket newPacket(ByteArrayOutputStream out);
}
