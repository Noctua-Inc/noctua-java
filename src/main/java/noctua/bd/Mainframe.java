
package noctua.bd;

public class Mainframe {

    private Integer idMainframe;
    private Integer idEmpresa;
    private String hostname;
    private Integer idComponente;
    private String tipo;
    private Integer picoMin;
    private Integer picoMax;

    public Mainframe(Integer idMainframe, Integer idEmpresa, String hostname,
                     Integer idComponente, String tipo, Integer picoMin, Integer picoMax) {
        this.idMainframe = idMainframe;
        this.idEmpresa = idEmpresa;
        this.hostname = hostname;
        this.idComponente = idComponente;
        this.tipo = tipo;
        this.picoMin = picoMin;
        this.picoMax = picoMax;
    }

    public int getIdMainframe() {
        return idMainframe;
    }

    public int getIdEmpresa() {
        return idEmpresa;
    }

    public String getHostname() {
        return hostname;
    }

    public int getIdComponente() {
        return idComponente;
    }

    public String getTipo() {
        return tipo;
    }

    public int getPicoMin() {
        return picoMin;
    }

    public int getPicoMax() {
        return picoMax;
    }
}
