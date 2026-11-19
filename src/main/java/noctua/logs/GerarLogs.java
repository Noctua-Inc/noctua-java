
package noctua.logs;

import java.io.FileWriter;
import java.io.IOException;

public class GerarLogs {

    public void registrar(String horario, String hostname, Double cpu, String statusCpu,
                          Double ram, String statusRam,
                          Double disco, String statusDisco) throws IOException {
        try (FileWriter arquivo = new FileWriter("noctua.log")) {

            arquivo.write(String.format(
                    "[%s] LOG %s - CPU: %.1f%% %s | RAM: %.1f%% %s | DISCO: %.1f%% %s%n",
                    horario, hostname, cpu, statusCpu, ram, statusRam, disco, statusDisco
            ));

        }
    }
}
