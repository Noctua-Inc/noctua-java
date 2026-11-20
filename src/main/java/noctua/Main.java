package noctua;

import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.cdimascio.dotenv.Dotenv;

import noctua.bd.DBConnectionProvider;
import noctua.bd.Mainframe;
import noctua.jira.config.Jira;
import noctua.logs.GerarLogs;
import noctua.s3.S3Provider;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Main {

    public static void main(String[] args) {

        Dotenv dotenv = Dotenv.load();

        String baseUrl = dotenv.get("JIRA_BASE_URL");
        String email = dotenv.get("JIRA_EMAIL");
        String apiToken = dotenv.get("JIRA_API_TOKEN");

        String bucketName = dotenv.get("BUCKET_NAME", "bucket-raw-rnaleto");
        System.out.println("Bucket S3: " + bucketName);
        System.out.println("Envio ao Jira ativado.");
        System.out.println("Consultando empresas e limites no MySQL...");

        DBConnectionProvider dbConnectionProvider = new DBConnectionProvider();
        JdbcTemplate conexao = dbConnectionProvider.getConnection();
        List<Mainframe> mainframes;
        List<Empresa> empresas = new ArrayList<>();

        try {
            List<Empresa> linhasEmpresa = conexao.query(
                    "SELECT id_empresa, razao_social FROM empresa", new EmpresaMapper());
            for (Empresa empresa : linhasEmpresa) {
                adicionarEmpresa(empresas, empresa);
            }
            mainframes = conexao.query(
                    "SELECT m.id_mainframe, m.fk_empresa, m.hostname, " +
                            "c.id_componente, c.tipo, p.pico_min, p.pico_max " +
                            "FROM mainframe m " +
                            "JOIN parametro p ON p.id_mainframe = m.id_mainframe " +
                            "JOIN componente c ON c.id_componente = p.id_componente",

                    new MainframeMapper()
            );
        } catch (Exception e) {
            System.err.println(
                    "Erro ao consultar mainframes: " + e.getMessage()
            );
            return;
        }


        Jira jira = new Jira(baseUrl, email, apiToken);
        System.out.println("MySQL: " + empresas.size() + " pasta(s) de empresa e "
                + mainframes.size() + " registro(s) de parâmetros carregados.");
        int lotesConcluidos = 0;
        int totalAlertas = 0;
        try (S3Client s3Client = new S3Provider().getS3Client()) {
            for (Empresa empresa : empresas) {
                if (empresa.id == 0 || empresa.pastaAmbigua) {
                    System.err.println("Pasta Silver ambígua para a empresa: " + empresa.pasta);
                    continue;
                }
                String prefixo = empresa.pasta + "/02-silver/";
                System.out.println("Consultando prefixo Silver: " + prefixo);
                int lotesEncontrados = 0;
                ListObjectsV2Request requisicao = ListObjectsV2Request.builder()
                        .bucket(bucketName).prefix(prefixo).build();

                for (S3Object objeto : s3Client.listObjectsV2Paginator(requisicao).contents()) {
                    String chave = objeto.key();
                    String hostname = hostnameDoLote(prefixo, chave);
                    if (hostname == null) {
                        continue;
                    }
                    lotesEncontrados++;
                    int quantidadeAlertas = processarLote(s3Client, jira, bucketName, empresa,
                            chave, hostname, mainframes);
                    if (quantidadeAlertas >= 0) {
                        lotesConcluidos++;
                        totalAlertas += quantidadeAlertas;
                    }
                }
                System.out.println("Lotes de métricas encontrados em " + prefixo + ": " + lotesEncontrados);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Envio ao Jira interrompido.");
            return;
        } catch (SdkException e) {
            throw new IllegalStateException("Erro ao consultar a Silver no bucket " + bucketName, e);
        }
        System.out.println("\nProcessamento finalizado. Lotes lidos: " + lotesConcluidos + " | Alertas: " + totalAlertas);
    }

    private static void adicionarEmpresa(List<Empresa> empresas, Empresa novaEmpresa) {
        for (Empresa empresa : empresas) {
            if (empresa.pasta.equals(novaEmpresa.pasta)) {
                empresa.pastaAmbigua = true;
                return;
            }
        }
        empresas.add(novaEmpresa);
    }

    private static class Empresa {
        private final int id;
        private final String pasta;
        private final String razaoSocial;
        private boolean pastaAmbigua;

        private Empresa(int id, String pasta, String razaoSocial) {
            this.id = id;
            this.pasta = pasta;
            this.razaoSocial = razaoSocial;
        }
    }

    private static class EmpresaMapper implements RowMapper<Empresa> {
        @Override
        public Empresa mapRow(ResultSet linha, int numeroLinha) throws SQLException {
            String razaoSocial = linha.getString("razao_social");
            String pasta = razaoSocial.toLowerCase(Locale.ROOT).replace(' ', '_');
            return new Empresa(linha.getInt("id_empresa"), pasta, razaoSocial);
        }
    }

    private static class MainframeMapper implements RowMapper<Mainframe> {
        @Override
        public Mainframe mapRow(ResultSet linha, int numeroLinha) throws SQLException {
            return new Mainframe(
                    linha.getInt("id_mainframe"),
                    linha.getInt("fk_empresa"),
                    linha.getString("hostname"),
                    linha.getInt("id_componente"),
                    linha.getString("tipo"),
                    linha.getInt("pico_min"),
                    linha.getInt("pico_max")
            );
        }
    }

    private static int processarLote(S3Client s3, Jira jira, String bucket, Empresa empresa, String chave,
                                   String hostname, List<Mainframe> mainframes)
            throws InterruptedException {
        String nome = chave.substring(chave.lastIndexOf('/') + 1, chave.length() - ".csv".length());
        String marcador = empresa.pasta + "/04-controle/" + hostname + "/" + nome + ".concluido";
        if (marcadorExiste(s3, bucket, marcador)) {
            System.out.println("Lote já processado: " + chave);
            return -1;
        }

        System.out.println("Processando arquivo: " + chave);
        GetObjectRequest download = GetObjectRequest.builder().bucket(bucket).key(chave).build();
        List<String> alertas;
        ArrayNode alertasGold = AlertasGold.novoArray();
        try (BufferedReader leitor = new BufferedReader(new InputStreamReader(
                s3.getObject(download), StandardCharsets.UTF_8))) {
            alertas = processarCsv(leitor, chave, hostname, empresa.id, mainframes,
                    empresa.razaoSocial, alertasGold);
        } catch (IOException e) {
            System.err.println("Erro ao processar " + chave + ": " + e.getMessage());
            return -1;
        }

        String chaveGold = empresa.pasta + "/03-gold/alertas.json";
        try {
            AlertasGold.atualizar(s3, bucket, chaveGold, alertasGold);
        } catch (IOException | SdkException e) {
            System.err.println("Erro ao atualizar s3://" + bucket + "/" + chaveGold + ": " + e.getMessage());
            return -1;
        }

        System.out.println("\n===== ALERTAS ENCONTRADOS =====");
        if (alertas.isEmpty()) {
            System.out.println("Nenhum alerta encontrado neste arquivo.");
        }
        for (String alerta : alertas) {
            System.out.println(alerta);
            try {
                String resposta = jira.createIssue("SUP", alerta, "Tarefa");
                System.out.println("Chamado criado no Jira: " + resposta);
            } catch (IOException | RuntimeException e) {
                System.err.println("Erro ao criar chamado para " + chave + ": " + e.getMessage());
                return -1;
            }
        }

        PutObjectRequest pedido = PutObjectRequest.builder().bucket(bucket).key(marcador).build();
        s3.putObject(pedido, RequestBody.empty());
        System.out.println("Marcador gravado: " + marcador);
        return alertas.size();
    }

    private static boolean marcadorExiste(S3Client s3, String bucket, String marcador) {
        HeadObjectRequest pedido = HeadObjectRequest.builder().bucket(bucket).key(marcador).build();
        try {
            s3.headObject(pedido);
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    static String hostnameDoLote(String prefixo, String chave) {
        if (!chave.startsWith(prefixo)) {
            return null;
        }
        String caminhoLote = chave.substring(prefixo.length());
        String[] partes = caminhoLote.split("/", -1);
        if (partes.length != 2 || partes[0].isBlank()) {
            return null;
        }
        String hostname = partes[0];
        String inicioLote = hostname + "_";
        if (partes[1].startsWith(inicioLote)
                && partes[1].substring(inicioLote.length()).matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}\\.csv")) {
            return hostname;
        }
        return null;
    }

    static List<String> processarCsv(BufferedReader leitor, String chave, String hostnamePasta,
                                    int idEmpresa, List<Mainframe> mainframes,
                                    String razaoSocial, ArrayNode alertasGold) throws IOException {
        List<String> alertas = new ArrayList<>();
        String cabecalho = leitor.readLine();
        if (cabecalho == null) {
            throw new IOException("CSV sem cabeçalho.");
        }

        String[] colunas = cabecalho.replace("\uFEFF", "").split(",", -1);
        int colunaHost = indice(colunas, "HostName");
        int colunaCpu = indice(colunas, "Porcentagem_CPU");
        int colunaRam = indice(colunas, "Porcentagem_RAM");
        int colunaDisco = indice(colunas, "Porcentagem_Disco");
        if (colunaHost < 0 || colunaCpu < 0 || colunaRam < 0 || colunaDisco < 0) {
            throw new IOException("Cabeçalho de métricas inválido.");
        }
        int ultimaColuna = Math.max(colunaHost, colunaCpu);
        ultimaColuna = Math.max(ultimaColuna, colunaRam);
        ultimaColuna = Math.max(ultimaColuna, colunaDisco);

        String linha;
        int numeroLinha = 1;
        boolean registrosInvalidos = false;
        while ((linha = leitor.readLine()) != null) {
            numeroLinha++;
            if (linha.isBlank()) {
                continue;
            }
            String[] dados = linha.split(",", -1);
            if (dados.length <= ultimaColuna) {
                System.err.println("Linha incompleta em " + chave + ":" + numeroLinha);
                registrosInvalidos = true;
                continue;
            }
            String hostname = dados[colunaHost].trim();
            if (!hostnamePasta.equalsIgnoreCase(hostname)) {
                System.err.println("Hostname diferente da pasta em " + chave + ":" + numeroLinha);
                registrosInvalidos = true;
                continue;
            }

            double porcentagemCpu;
            double porcentagemRam;
            double porcentagemDisco;
            try {
                porcentagemCpu = percentual(dados[colunaCpu]);
                porcentagemRam = percentual(dados[colunaRam]);
                porcentagemDisco = percentual(dados[colunaDisco]);
            } catch (NumberFormatException e) {
                System.err.println("Métrica inválida em " + chave + ":" + numeroLinha);
                registrosInvalidos = true;
                continue;
            }

            Integer idMainframe = null;
            boolean ambiguo = false;
            for (Mainframe mainframe : mainframes) {
                if (corresponde(mainframe, idEmpresa, hostname)) {
                    if (idMainframe != null && idMainframe != mainframe.getIdMainframe()) {
                        ambiguo = true;
                        break;
                    }
                    idMainframe = mainframe.getIdMainframe();
                }
            }
            if (ambiguo) {
                System.err.println("Mainframe ambíguo na empresa " + idEmpresa + ": " + hostname);
                registrosInvalidos = true;
                continue;
            }

            System.out.println("\nHostname: " + hostname);
            System.out.println("CPU: " + porcentagemCpu + "%");
            System.out.println("RAM: " + porcentagemRam + "%");
            System.out.println("Disco: " + porcentagemDisco + "%");

            boolean encontrado = false;

            String statusCpu = "NORMAL";
            String statusRam = "NORMAL";
            String statusDisco = "NORMAL";

            for (Mainframe mainframe : mainframes) {

                if (!corresponde(mainframe, idEmpresa, hostname)) {
                    continue;
                }

                encontrado = true;

                System.out.println(
                        "Mainframe encontrado no banco: " +
                                mainframe.getIdMainframe()
                );

                String tipo = mainframe.getTipo();

                Double valor = null;

                if ("CPU".equalsIgnoreCase(tipo)) {
                    valor = porcentagemCpu;

                } else if ("RAM".equalsIgnoreCase(tipo)) {
                    valor = porcentagemRam;

                } else if ("Disco".equalsIgnoreCase(tipo)) {
                    valor = porcentagemDisco;
                }

                if (valor == null) {
                    System.out.println(
                            "Componente não reconhecido: " + tipo
                    );
                    continue;
                }

                Integer minimo = mainframe.getPicoMin();
                Integer maximo = mainframe.getPicoMax();

                System.out.println("Componente: " + tipo);
                System.out.println("Valor: " + valor + "%");
                System.out.println("Mínimo: " + minimo + "%");
                System.out.println("Máximo: " + maximo + "%");

                if (valor < minimo || valor > maximo) {

                    String alerta;

                    if (valor < minimo) {
                        alerta = hostname + " - " + tipo +
                                " abaixo do limite mínimo: " +
                                valor + "% (mínimo: " +
                                minimo + "%)";

                    } else {
                        alerta = hostname + " - " + tipo +
                                " acima do limite máximo: " +
                                valor + "% (máximo: " +
                                maximo + "%)";
                    }

                    alertas.add(alerta);
                    AlertasGold.adicionar(alertasGold, chave, mainframe.getIdMainframe(),
                            hostname, tipo, valor, minimo, maximo, razaoSocial);

                    System.out.println("ALERTA: " + alerta);

                    if ("CPU".equalsIgnoreCase(tipo)) {
                        statusCpu = "ALERTA";

                    } else if ("RAM".equalsIgnoreCase(tipo)) {
                        statusRam = "ALERTA";

                    } else if ("Disco".equalsIgnoreCase(tipo)) {
                        statusDisco = "ALERTA";
                    }

                } else {
                    System.out.println("Dentro dos limites.");
                }
            }

            if (!encontrado) {
                System.out.println(
                        "Mainframe não cadastrado: " + hostname
                );
            }


            GerarLogs log = new GerarLogs();

            LocalDateTime agora = LocalDateTime.now();

            DateTimeFormatter formato = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

            String horario = agora.format(formato);

            log.registrar(
                    horario,
                    hostname,
                    porcentagemCpu,
                    statusCpu,
                    porcentagemRam,
                    statusRam,
                    porcentagemDisco,
                    statusDisco
            );

            System.out.println("Log registrado!");
        }
        if (registrosInvalidos) {
            throw new IOException("CSV contém registros inválidos.");
        }
        return alertas;
    }

    private static boolean corresponde(Mainframe mainframe, int idEmpresa, String hostname) {
        return mainframe.getIdEmpresa() == idEmpresa && mainframe.getHostname() != null
                && mainframe.getHostname().trim().equalsIgnoreCase(hostname);
    }

    private static int indice(String[] colunas, String nome) {
        for (int i = 0; i < colunas.length; i++) {
            if (nome.equalsIgnoreCase(colunas[i].trim())) {
                return i;
            }
        }
        return -1;
    }

    private static double percentual(String texto) {
        double valor = Double.parseDouble(texto.trim());
        if (!Double.isFinite(valor) || valor < 0 || valor > 100) {
            throw new NumberFormatException("Percentual fora do intervalo de 0 a 100");
        }
        return valor;
    }
}
