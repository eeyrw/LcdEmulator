package com.yuan.lcmemulator;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public final class NetworkUtils {

    private NetworkUtils() {
        // 防止实例化
    }

    /**
     * 获取当前设备的 IPv4 地址
     */
    public static String getIpv4Address() {
        try {
            for (Enumeration<NetworkInterface> interfaces =
                 NetworkInterface.getNetworkInterfaces();
                 interfaces.hasMoreElements(); ) {

                NetworkInterface network = interfaces.nextElement();

                for (Enumeration<InetAddress> addresses =
                     network.getInetAddresses();
                     addresses.hasMoreElements(); ) {

                    InetAddress address = addresses.nextElement();

                    if (address instanceof Inet4Address
                            && !address.isLoopbackAddress()) {

                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
        }

        return "";
    }
}
