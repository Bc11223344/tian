package com.tiandaonb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity {
    
    private Switch switchHosts;
    private TextView statusText;
    private TextView statsText;
    private Button btnImport, btnExport, btnEdit;
    private static final int VPN_REQUEST_CODE = 1000;
    
    // 规则集合
    private Set<String> blockedDomains = new HashSet<>();
    private Set<String> blockedIPs = new HashSet<>();
    private Set<Integer> blockedPorts = new HashSet<>();
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // 初始化控件
        switchHosts = findViewById(R.id.switch_hosts);
        statusText = findViewById(R.id.status_text);
        statsText = findViewById(R.id.stats_text);
        btnImport = findViewById(R.id.btn_import);
        btnExport = findViewById(R.id.btn_export);
        btnEdit = findViewById(R.id.btn_edit);
        
        // 加载保存的规则
        loadRules();
        updateStats();
        
        // 检查Root权限
        if (!hasRoot()) {
            Toast.makeText(this, "需要Root权限才能使用iptables拦截", Toast.LENGTH_LONG).show();
            switchHosts.setEnabled(false);
        }
        
        // 开关监听
        switchHosts.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    startVpn();
                } else {
                    stopVpn();
                }
            }
        });
        
        // 按钮监听
        btnImport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showImportDialog();
            }
        });
        
        btnExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportRules();
            }
        });
        
        btnEdit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEditDialog();
            }
        });
    }
    
    /**
     * 检查是否有Root权限
     */
    private boolean hasRoot() {
        try {
            Process process = Runtime.getRuntime().exec("su -c 'echo test'");
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 启动VPN
     */
    private void startVpn() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE);
        } else {
            startVpnService();
        }
    }
    
    /**
     * 启动VPN服务
     */
    private void startVpnService() {
        // 保存规则到文件供服务读取
        saveRulesToFile();
        
        Intent intent = new Intent(this, DnsVpnService.class);
        intent.putExtra("config_file", getConfigFilePath());
        startService(intent);
        
        statusText.setText("状态：已启用");
        Toast.makeText(this, "拦截服务已启动", Toast.LENGTH_SHORT).show();
    }
    
    /**
     * 停止VPN
     */
    private void stopVpn() {
        Intent intent = new Intent(this, DnsVpnService.class);
        stopService(intent);
        statusText.setText("状态：已禁用");
        Toast.makeText(this, "拦截服务已停止", Toast.LENGTH_SHORT).show();
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startVpnService();
            } else {
                switchHosts.setChecked(false);
                Toast.makeText(this, "需要VPN权限才能使用", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    /**
     * 显示导入对话框
     */
    private void showImportDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("导入规则");
        
        final EditText input = new EditText(this);
        input.setHint("粘贴hosts格式的规则，例如：\n127.0.0.1 ads.com\n127.0.0.1 17500");
        input.setMinLines(15);
        input.setVerticalScrollBarEnabled(true);
        builder.setView(input);
        
        builder.setPositiveButton("导入", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String content = input.getText().toString();
                if (parseHostsContent(content)) {
                    saveRules();
                    updateStats();
                    Toast.makeText(MainActivity.this, "导入成功", Toast.LENGTH_SHORT).show();
                    
                    // 如果服务正在运行，重启以应用新规则
                    if (switchHosts.isChecked()) {
                        restartVpn();
                    }
                } else {
                    Toast.makeText(MainActivity.this, "解析失败，请检查格式", Toast.LENGTH_LONG).show();
                }
            }
        });
        
        builder.setNegativeButton("取消", null);
        builder.show();
    }
    
    /**
     * 解析hosts格式内容
     */
    private boolean parseHostsContent(String content) {
        try {
            Set<String> newDomains = new HashSet<>();
            Set<String> newIPs = new HashSet<>();
            Set<Integer> newPorts = new HashSet<>();
            
            String[] lines = content.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    String ip = parts[0];
                    String target = parts[1];
                    
                    // 只处理 127.0.0.1 和 0.0.0.0 的规则
                    if (ip.equals("127.0.0.1") || ip.equals("0.0.0.0")) {
                        
                        // 判断是端口、IP还是域名
                        if (target.matches("\\d+")) {
                            // 纯数字 -> 端口
                            newPorts.add(Integer.parseInt(target));
                        } else if (target.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                            // IP地址
                            newIPs.add(target);
                        } else {
                            // 域名
                            newDomains.add(target.toLowerCase());
                        }
                    }
                }
            }
            
            // 更新规则
            blockedDomains = newDomains;
            blockedIPs = newIPs;
            blockedPorts = newPorts;
            
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 显示编辑对话框
     */
    private void showEditDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("编辑域名规则");
        
        final EditText input = new EditText(this);
        input.setHint("每行一个域名，支持通配符如 .qq.com");
        input.setMinLines(20);
        input.setVerticalScrollBarEnabled(true);
        
        // 显示当前规则
        StringBuilder sb = new StringBuilder();
        for (String domain : blockedDomains) {
            sb.append(domain).append("\n");
        }
        input.setText(sb.toString());
        
        builder.setView(input);
        
        builder.setPositiveButton("保存", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String content = input.getText().toString();
                String[] lines = content.split("\n");
                
                Set<String> newDomains = new HashSet<>();
                for (String line : lines) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        newDomains.add(line.toLowerCase());
                    }
                }
                
                blockedDomains = newDomains;
                saveRules();
                updateStats();
                
                if (switchHosts.isChecked()) {
                    restartVpn();
                }
                
                Toast.makeText(MainActivity.this, "已保存", Toast.LENGTH_SHORT).show();
            }
        });
        
        builder.setNegativeButton("取消", null);
        builder.show();
    }
    
    /**
     * 导出规则到文件
     */
    private void exportRules() {
        try {
            // 检查外部存储权限
            if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                Toast.makeText(this, "无法访问外部存储", Toast.LENGTH_LONG).show();
                return;
            }
            
            File file = new File(Environment.getExternalStorageDirectory(), "hosts_rules.txt");
            FileOutputStream fos = new FileOutputStream(file);
            OutputStreamWriter writer = new OutputStreamWriter(fos);
            
            writer.write("# 域名规则\n");
            for (String domain : blockedDomains) {
                writer.write("127.0.0.1 " + domain + "\n");
            }
            
            writer.write("\n# IP规则\n");
            for (String ip : blockedIPs) {
                writer.write("127.0.0.1 " + ip + "\n");
            }
            
            writer.write("\n# 端口规则\n");
            for (int port : blockedPorts) {
                writer.write("127.0.0.1 " + port + "\n");
            }
            
            writer.close();
            fos.close();
            
            Toast.makeText(this, "已导出到: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * 保存规则到SharedPreferences
     */
    private void saveRules() {
        SharedPreferences prefs = getSharedPreferences("rules", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        
        // 保存域名
        StringBuilder domainsStr = new StringBuilder();
        for (String d : blockedDomains) {
            if (domainsStr.length() > 0) domainsStr.append("\n");
            domainsStr.append(d);
        }
        editor.putString("domains", domainsStr.toString());
        
        // 保存IP
        StringBuilder ipsStr = new StringBuilder();
        for (String ip : blockedIPs) {
            if (ipsStr.length() > 0) ipsStr.append("\n");
            ipsStr.append(ip);
        }
        editor.putString("ips", ipsStr.toString());
        
        // 保存端口
        StringBuilder portsStr = new StringBuilder();
        for (int port : blockedPorts) {
            if (portsStr.length() > 0) portsStr.append("\n");
            portsStr.append(port);
        }
        editor.putString("ports", portsStr.toString());
        
        editor.apply();
    }
    
    /**
     * 加载规则
     */
    private void loadRules() {
        SharedPreferences prefs = getSharedPreferences("rules", MODE_PRIVATE);
        
        String domainsStr = prefs.getString("domains", "");
        String ipsStr = prefs.getString("ips", "");
        String portsStr = prefs.getString("ports", "");
        
        // 解析域名
        blockedDomains = new HashSet<>();
        if (!domainsStr.isEmpty()) {
            for (String d : domainsStr.split("\n")) {
                blockedDomains.add(d);
            }
        }
        
        // 解析IP
        blockedIPs = new HashSet<>();
        if (!ipsStr.isEmpty()) {
            for (String ip : ipsStr.split("\n")) {
                blockedIPs.add(ip);
            }
        }
        
        // 解析端口
        blockedPorts = new HashSet<>();
        if (!portsStr.isEmpty()) {
            for (String p : portsStr.split("\n")) {
                try {
                    blockedPorts.add(Integer.parseInt(p));
                } catch (NumberFormatException ignored) {}
            }
        }
    }
    
    /**
     * 保存规则到文件（供服务读取）
     */
    private void saveRulesToFile() {
        try {
            FileOutputStream fos = openFileOutput("config.txt", MODE_PRIVATE);
            OutputStreamWriter writer = new OutputStreamWriter(fos);
            
            // 写入域名
            for (String domain : blockedDomains) {
                writer.write("domain:" + domain + "\n");
            }
            
            // 写入IP
            for (String ip : blockedIPs) {
                writer.write("ip:" + ip + "\n");
            }
            
            // 写入端口
            for (int port : blockedPorts) {
                writer.write("port:" + port + "\n");
            }
            
            writer.close();
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 获取配置文件路径
     */
    private String getConfigFilePath() {
        return getFilesDir() + "/config.txt";
    }
    
    /**
     * 更新统计显示
     */
    private void updateStats() {
        statsText.setText(
            "域名: " + blockedDomains.size() + "条  |  " +
            "IP: " + blockedIPs.size() + "条  |  " +
            "端口: " + blockedPorts.size() + "条"
        );
    }
    
    /**
     * 重启VPN服务
     */
    private void restartVpn() {
        stopVpn();
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {}
        startVpn();
    }
}