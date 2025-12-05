/*
 * Copyright (C) 2015-2018 Federico Dossena
 * Copyright (C) 2025 Ice
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/lgpl>.
 */

package com.dosse.upnp;

import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;

/**
 * A Java 11 Modernized UPnP Port Forwarding Library.
 */
public class UPnP {

    private static final boolean DEBUG = false;
    private static final String DISCOVER_MESSAGE =
        "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\n" +
        "ST: urn:schemas-upnp-org:service:WANIPConnection:1\r\n" +
        "\r\n";

    private static final String SOAP_ACTION_BASE = "urn:schemas-upnp-org:service:WANIPConnection:1#";
    
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    private static String gatewayAddress = null;
    private static String controlUrl = null;
    private static InetAddress localAddress = null; // Cache the detected local IP

    // =============================================================
    // PUBLIC API
    // =============================================================

    public static boolean openPortTCP(int port) { return execute(port, "TCP", "AddPortMapping", null); }
    public static boolean openPortTCP(int port, String desc) { return execute(port, "TCP", "AddPortMapping", desc); }
    
    public static boolean openPortUDP(int port) { return execute(port, "UDP", "AddPortMapping", null); }
    public static boolean openPortUDP(int port, String desc) { return execute(port, "UDP", "AddPortMapping", desc); }

    public static boolean closePortTCP(int port) { return execute(port, "TCP", "DeletePortMapping", null); }
    public static boolean closePortUDP(int port) { return execute(port, "UDP", "DeletePortMapping", null); }

    public static boolean isMappedTCP(int port) { return execute(port, "TCP", "GetSpecificPortMappingEntry", null); }
    public static boolean isMappedUDP(int port) { return execute(port, "UDP", "GetSpecificPortMappingEntry", null); }

    // =============================================================
    // INTERNAL LOGIC
    // =============================================================

    private static boolean execute(int port, String protocol, String action, String description) {
        if (gatewayAddress == null || controlUrl == null) {
            if (!findGateway()) {
                if (DEBUG) System.out.print("Error: Could not find gateway.\n");
                return false;
            }
        }

        String soapAction = SOAP_ACTION_BASE + action;
        String body = buildSoapBody(action, port, protocol, description);

        // Debug output
        if (DEBUG) System.out.print("Transmitting: " + body + "\n\n");
        
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(controlUrl))
                .header("Content-Type", "text/xml")
                .header("SOAPAction", soapAction)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (DEBUG) {
                System.out.print("Response: " + response + "\n\n");
                System.out.print("Response Data: " + response.body() + "\n\n");
            }
            return response.statusCode() == 200;

        } catch (Exception e) {
            System.err.println("WaifUPnP: Request failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Attempts to find the UPnP Gateway.
     * FIX: Binds specifically to the Local IPv4 address to avoid Java 11 IPv6 issues.
     */
    private static boolean findGateway() {
        // 1. Ensure we have a valid local IPv4 address
        if (localAddress == null) {
            localAddress = getLocalInetAddress();
            if (localAddress == null) {
                System.err.println("WaifUPnP: Could not determine local IPv4 address.");
                return false;
            }
        }

        if (DEBUG) System.out.println("Scanning for UPnP Gateway using interface: " + localAddress.getHostAddress() + '\n');

        // 2. Bind the UDP socket specifically to this address
        try (var socket = new DatagramSocket(0, localAddress)) {
            socket.setSoTimeout(3000);
            
            var txPacket = new DatagramPacket(
                DISCOVER_MESSAGE.getBytes(StandardCharsets.UTF_8),
                DISCOVER_MESSAGE.length(),
                InetAddress.getByName("239.255.255.250"),
                1900
            );

            socket.send(txPacket);

            var rxBuffer = new byte[2048];
            
            while (true) {
                var rxPacket = new DatagramPacket(rxBuffer, rxBuffer.length);
                socket.receive(rxPacket);
                
                var response = new String(rxPacket.getData(), 0, rxPacket.getLength(), StandardCharsets.UTF_8);
                var location = parseLocation(response);
                
                if (location != null) {
                    gatewayAddress = location;
                    if (DEBUG) System.out.print("Found gateway at:" + location + "\n\n");
                    return fetchControlUrl(location);
                }
            }
        } catch (SocketTimeoutException e) {
            System.err.println("WaifUPnP: Timeout waiting for router response.");
            return false;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
    
    private static boolean fetchControlUrl(String location) {
        try {
            var request = HttpRequest.newBuilder().uri(URI.create(location)).GET().build();
            var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) return false;
            
            String xml = response.body();
            int start = xml.indexOf("urn:schemas-upnp-org:service:WANIPConnection:1");
            if (start == -1) return false;
            
            int controlStart = xml.indexOf("<controlURL>", start);
            int controlEnd = xml.indexOf("</controlURL>", controlStart);
            
            if (controlStart == -1 || controlEnd == -1) return false;
            
            String path = xml.substring(controlStart + 12, controlEnd).trim();
            
            if (path.startsWith("http")) {
                controlUrl = path;
            } else {
                 var uri = URI.create(location);
                 controlUrl = "http://" + uri.getHost() + ":" + uri.getPort() + path;
            }
            return true;
            
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static String parseLocation(String response) {
        for (String line : response.split("\\r?\\n")) {
            if (line.toLowerCase().startsWith("location:")) {
                return line.substring(9).trim();
            }
        }
        return null;
    }

    private static String buildSoapBody(String action, int port, String protocol, String description) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\"?>")
          .append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
          .append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
          .append("<s:Body>")
          .append("<u:").append(action).append(" xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:1\">");

        if (action.equals("AddPortMapping")) {
            // FIX: We must send the actual IP, not localhost, or the router will reject it.
            String clientIP = (localAddress != null) ? localAddress.getHostAddress() : "127.0.0.1";

            sb.append("<NewRemoteHost></NewRemoteHost>")
              .append("<NewExternalPort>").append(port).append("</NewExternalPort>")
              .append("<NewProtocol>").append(protocol).append("</NewProtocol>")
              .append("<NewInternalPort>").append(port).append("</NewInternalPort>")
              .append("<NewInternalClient>").append(clientIP).append("</NewInternalClient>")
              .append("<NewEnabled>1</NewEnabled>")
              .append("<NewPortMappingDescription>").append(description == null ? "WaifUPnP" : description).append("</NewPortMappingDescription>")
              .append("<NewLeaseDuration>0</NewLeaseDuration>");
        } else if (action.equals("DeletePortMapping") || action.equals("GetSpecificPortMappingEntry")) {
             sb.append("<NewRemoteHost></NewRemoteHost>")
               .append("<NewExternalPort>").append(port).append("</NewExternalPort>")
               .append("<NewProtocol>").append(protocol).append("</NewProtocol>");
        }

        sb.append("</u:").append(action).append(">")
          .append("</s:Body>")
          .append("</s:Envelope>");
        
        return sb.toString();
    }
    
    /**
     * Improved IP detection. 
     * Finds the first non-loopback IPv4 address on an active interface.
     */
    private static InetAddress getLocalInetAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(interfaces)) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                for (InetAddress addr : Collections.list(addresses)) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}