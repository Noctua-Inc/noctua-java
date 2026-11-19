
package noctua.bd;

public class Captura {

    private String captureId;
    private String hostname;
    private Double porcentagemCpu;
    private Double freqCpu;
    private Double porcentagemRam;
    private Long totalRam;
    private Long disponivelRam;
    private Double porcentagemDisco;
    private Long disponivelDisco;
    private Long swap;
    private Double load;
    private String timestamp;

    public Captura(String captureId, String hostname,
                   Double porcentagemCpu, Double freqCpu,
                   Double porcentagemRam, Long totalRam,
                   Long disponivelRam, Double porcentagemDisco,
                   Long disponivelDisco, Long swap,
                   Double load, String timestamp) {

        this.captureId = captureId;
        this.hostname = hostname;
        this.porcentagemCpu = porcentagemCpu;
        this.freqCpu = freqCpu;
        this.porcentagemRam = porcentagemRam;
        this.totalRam = totalRam;
        this.disponivelRam = disponivelRam;
        this.porcentagemDisco = porcentagemDisco;
        this.disponivelDisco = disponivelDisco;
        this.swap = swap;
        this.load = load;
        this.timestamp = timestamp;
    }

    public String getCaptureId() {
        return captureId;
    }

    public String getHostname() {
        return hostname;
    }

    public Double getPorcentagemCpu() {
        return porcentagemCpu;
    }

    public Double getFreqCpu() {
        return freqCpu;
    }

    public Double getPorcentagemRam() {
        return porcentagemRam;
    }

    public Long getTotalRam() {
        return totalRam;
    }

    public Long getDisponivelRam() {
        return disponivelRam;
    }

    public Double getPorcentagemDisco() {
        return porcentagemDisco;
    }

    public Long getDisponivelDisco() {
        return disponivelDisco;
    }

    public Long getSwap() {
        return swap;
    }

    public Double getLoad() {
        return load;
    }

    public String getTimestamp() {
        return timestamp;
    }
}
