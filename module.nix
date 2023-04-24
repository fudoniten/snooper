packages:

{ config, lib, pkgs, ... }:

with lib;
let
  snooper-server = packages."${pkgs.system}".snooper-server;
  cfg = config.services.snooper;

in {
  options.services.snooper = with types; {
    enable = mkEnableOption "Enable Snooper notifiaction server.";

    verbose = mkEnableOption "Generate verbose logs and output.";

    event-topics = mkOption {
      type = listOf str;
      description = "MQTT topics on which to listen for detection events.";
    };

    notification-topic = mkOption {
      type = str;
      description = "MQTT topic on which to send notifications.";
    };

    mqtt = {
      host = mkOption {
        type = str;
        description = "Hostname of the MQTT server.";
      };

      port = mkOption {
        type = port;
        description = "Port on which the MQTT server is listening.";
        default = 1883;
      };

      username = mkOption {
        type = str;
        description = "User as which to connect to the MQTT server.";
      };

      password-file = mkOption {
        type = str;
        description =
          "File (on the local host) containing the password for the MQTT server.";
      };
    };
  };

  config = mkIf cfg.enable {
    systemd.services.snooper = {
      path = [ snooper-server ];
      wantedBy = [ "multi-user.target" ];
      serviceConfig = {
        DynamicUser = true;
        LoadCredential = [ "mqtt.passwd:${cfg.mqtt.password-file}" ];
        ExecStart = pkgs.writeShellScript "snooper-server.sh"
          (concatStringsSep " " ([
            "snooper-server"
            "--mqtt-host=${cfg.mqtt.host}"
            "--mqtt-port=${toString cfg.mqtt.port}"
            "--mqtt-user=${cfg.mqtt.username}"
            "--mqtt-password-file=$CREDENTIALS_DIRECTORY/mqtt.passwd"
            "--notification-topic=${cfg.notification-topic}"
          ] ++ (map (topic: "--event-topic=${topic}") cfg.event-topics)
            ++ (optional cfg.verbose "--verbose")));
      };
    };
  };
}
