package com.tiandaonb;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.xbill.DNS.Flags;
import org.xbill.DNS.Header;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DnsVpnService extends VpnService {
    
    private static final String TAG = "DnsVpnService";
    private static final String VPN_ADDRESS = "10.0.0.2";
    private static final String VPN_ROUTE = "0.0.0.0";
    private static final int DNS_PORT = 53;
    private static final String UPSTREAM_DNS = "8.8.8.8";
    
    private ExecutorService executorService;
    private DatagramSocket dnsSocket;
    private boolean isRunning = true;
    
    // 规则集合
    private Set<String> blockedDomains = new HashSet<>();
    private Set<String> blockedIPs = new HashSet<>();
    private Set<Integer> blockedPorts = new HashSet<>();
    
    // 通配符规则
    private Set<String> wildcardDomains = new HashSet<>();
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String configFile = intent.getStringExtra("config_file");
        if (configFile != null) {
            loadConfig(configFile);
        }
        
        startVpn();
        setupIptables();
        
        return START_STICKY;
    }
    
    /**
     * 加载配置文件
     */
    private void loadConfig(String filePath) {
        try {
            FileInputStream fis = new FileInputStream(filePath);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("domain:")) {
                    String domain = line.substring(7).toLowerCase();
                    if (domain.startsWith(".")) {
                        wildcardDomains.add(domain);
                    } else {
                        blockedDomains.add(domain);
                    }
                } else if (line.startsWith("ip:")) {
                    blockedIPs.add(line.substring(3));
                } else if (line.startsWith("port:")) {
                    try {
                        blockedPorts.add(Integer.parseInt(line.substring(5)));
                    } catch (NumberFormatException ignored) {}
                }
            }
            
            reader.close();
            fis.close();
            
            Log.i(TAG, "加载配置完成");
            Log.i(TAG, "域名: " + blockedDomains.size());
            Log.i(TAG, "通配: " + wildcardDomains.size());
            Log.i(TAG, "IP: " + blockedIPs.size());
            Log.i(TAG, "端口: " + blockedPorts.size());
            
        } catch (Exception e) {
            Log.e(TAG, "加载配置失败", e);
        }
    }
    
    /**
     * 设置iptables规则
     */
    private void setupIptables() {
        try {
            Process su = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(su.getOutputStream());
            
            // 清除旧规则
            os.writeBytes("iptables -F OUTPUT\n");
            
            // 放行本APP
            int uid = android.os.Process.myUid();
            os.writeBytes("iptables -A OUTPUT -m owner --uid-owner " + uid + " -j ACCEPT\n");
            
            // 拦截IP
            for (String ip : blockedIPs) {
                os.writeBytes("iptables -A OUTPUT -d " + ip + " -j DROP\n");
                Log.d(TAG, "添加IP拦截: " + ip);
            }
            
            // 拦截端口
            for (int port : blockedPorts) {
                os.writeBytes("iptables -A OUTPUT -p tcp --dport " + port + " -j REJECT\n");
                os.writeBytes("iptables -A OUTPUT -p udp --dport " + port + " -j REJECT\n");
                Log.d(TAG, "添加端口拦截: " + port);
            }
            
            os.writeBytes("exit\n");
            os.flush();
            su.waitFor();
            
            Log.i(TAG, "iptables规则设置完成");
        } catch (Exception e) {
            Log.e(TAG, "iptables设置失败", e);
        }
    }
    
    /**
     * 清除iptables规则
     */
    private void clearIptables() {
        try {
            Process su = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(su.getOutputStream());
            os.writeBytes("iptables -F OUTPUT\n");
            os.writeBytes("exit\n");
            os.flush();
            su.waitFor();
            Log.i(TAG, "iptables规则已清除");
        } catch (Exception e) {
            Log.e(TAG, "iptables清除失败", e);
        }
    }
    
    /**
     * 启动VPN
     */
    private void startVpn() {
        try {
            Builder builder = new Builder();
            builder.setSessionName("天道拦截器");
            builder.addAddress(VPN_ADDRESS, 32);
            builder.addRoute(VPN_ROUTE, 0);
            builder.addDnsServer(UPSTREAM_DNS);
            
            ParcelFileDescriptor vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "VPN建立失败");
                return;
            }
            
            // 创建DNS socket
            dnsSocket = new DatagramSocket(DNS_PORT, InetAddress.getByName(VPN_ADDRESS));
            protect(dnsSocket.getFileDescriptor$().getInt$());
            
            executorService = Executors.newSingleThreadExecutor();
            executorService.submit(new DnsRunnable());
            
            Log.i(TAG, "VPN服务已启动");
            
        } catch (Exception e) {
            Log.e(TAG, "VPN启动异常", e);
        }
    }
    
    /**
     * DNS处理线程
     */
    private class DnsRunnable implements Runnable {
        private final byte[] buffer = new byte[65536];
        
        @Override
        public void run() {
            while (isRunning && dnsSocket != null && !dnsSocket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    dnsSocket.receive(packet);
                    
                    // 解析DNS请求
                    Message query = new Message(packet.getData());
                    Record question = query.getQuestion();
                    
                    if (question != null) {
                        Name name = question.getName();
                        String domain = name.toString().toLowerCase();
                        
                        // 去掉末尾的点
                        if (domain.endsWith(".")) {
                            domain = domain.substring(0, domain.length() - 1);
                        }
                        
                        handleDnsQuery(packet, query, domain);
                    }
                    
                } catch (Exception e) {
                    if (isRunning) {
                        Log.e(TAG, "DNS处理异常", e);
                    }
                }
            }
        }
        
        /**
         * 处理DNS查询
         */
        private void handleDnsQuery(DatagramPacket packet, Message query, String domain) throws Exception {
            if (shouldBlockDomain(domain)) {
                sendBlockResponse(packet, query);
                Log.i(TAG, "拦截域名: " + domain);
            } else {
                forwardDnsRequest(packet, query, domain);
            }
        }
        
        /**
         * 判断是否拦截
         */
        private boolean shouldBlockDomain(String domain) {
            // 精确匹配
            if (blockedDomains.contains(domain)) {
                return true;
            }
            
            // 通配符匹配
            for (String wildcard : wildcardDomains) {
                if (domain.endsWith(wildcard)) {
                    return true;
                }
            }
            
            // 常见后缀
            if (domain.endsWith(".qq.com") || 
                domain.endsWith(".tencent.com") ||
                domain.endsWith(".weixin.com") ||
                domain.contains("qq.com") ||
                domain.contains("tencent")) {
                return true;
            }
            
            return false;
        }
        
        /**
         * 转发DNS请求
         */
        private void forwardDnsRequest(DatagramPacket clientPacket, Message query, String domain) throws Exception {
            DatagramSocket forwardSocket = new DatagramSocket();
            protect(forwardSocket.getFileDescriptor$().getInt$());
            
            byte[] queryData = query.toWire();
            DatagramPacket forwardPacket = new DatagramPacket(
                queryData, queryData.length,
                InetAddress.getByName(UPSTREAM_DNS), DNS_PORT
            );
            
            forwardSocket.send(forwardPacket);
            
            // 接收响应
            byte[] responseBuffer = new byte[65536];
            DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
            forwardSocket.setSoTimeout(5000);
            forwardSocket.receive(responsePacket);
            
            // 转发回客户端
            responsePacket.setSocketAddress(clientPacket.getSocketAddress());
            dnsSocket.send(responsePacket);
            
            forwardSocket.close();
            Log.d(TAG, "放行域名: " + domain);
        }
        
        /**
         * 发送拦截响应
         */
        private void sendBlockResponse(DatagramPacket clientPacket, Message query) throws Exception {
            Message response = new Message();
            
            Header header = (Header) query.getHeader().clone();
            header.setFlag(Flags.QR);
            header.setRcode(Rcode.NXDOMAIN);
            response.setHeader(header);
            
            response.addRecord(query.getQuestion(), Section.QUESTION);
            
            byte[] responseData = response.toWire();
            DatagramPacket responsePacket = new DatagramPacket(
                responseData, responseData.length,
                clientPacket.getSocketAddress()
            );
            dnsSocket.send(responsePacket);
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        
        clearIptables();
        
        if (dnsSocket != null && !dnsSocket.isClosed()) {
            dnsSocket.close();
        }
        
        if (executorService != null) {
            executorService.shutdownNow();
        }
        
        Log.i(TAG, "服务已停止");
    }
}