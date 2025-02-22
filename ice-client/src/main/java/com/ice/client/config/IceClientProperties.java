package com.ice.client.config;

import com.ice.rmi.common.enums.RmiNetModeEnum;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ice")
public class IceClientProperties {
    /*
     * appId
     */
    private Integer app;
    /*
     * rmi config
     */
    private IceClientRmiProperties rmi = new IceClientRmiProperties();
    /*
     * ice thread pool
     */
    private IceClientThreadPoolProperties pool = new IceClientThreadPoolProperties();

    @Data
    public static class IceClientRmiProperties {
        private String server;
        private RmiNetModeEnum mode = RmiNetModeEnum.ONE_WAY;
        private int port = 0;
        private String serverHost;
        private int serverPort;

        public void setServer(String server) {
            this.server = server;
            String[] serverHostPort = server.split(":");
            try {
                this.serverHost = serverHostPort[0];
                this.serverPort = Integer.parseInt(serverHostPort[1]);
            } catch (Exception e) {
                throw new RuntimeException("ice server config error conf:" + server);
            }
        }
    }

    @Data
    public static class IceClientThreadPoolProperties {
        private int parallelism = -1;
    }
}
