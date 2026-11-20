package noctua;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

final class AlertasGold {
    private static final int MAX_TENTATIVAS = 5;
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter FORMATO_LOTE = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final ObjectMapper JACKSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    static ArrayNode novoArray() {
        return JACKSON.createArrayNode();
    }

    static void adicionar(ArrayNode alertas, String chaveSilver, int idMainframe,
                          String hostname, String componente, double valor, double minimo, double maximo,
                          String empresa) throws IOException {
        int fimData = chaveSilver.length() - ".csv".length();
        String dataLote = chaveSilver.substring(fimData - 16, fimData);
        LocalDateTime horario;
        try {
            horario = LocalDateTime.parse(dataLote, FORMATO_LOTE);
        } catch (DateTimeParseException e) {
            throw new IOException("Data inválida no nome do lote: " + chaveSilver, e);
        }

        ObjectNode alerta = alertas.addObject();
        alerta.put("id", idMainframe);
        alerta.put("hostname", hostname);
        alerta.put("componente", componente);
        alerta.put("valor", valor);
        alerta.put("limite_min", minimo);
        alerta.put("limite_max", maximo);
        String dataHora = horario.atZone(FUSO).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        alerta.put("data_hora", dataHora);
        alerta.put("empresa", empresa);
    }

    static void atualizar(S3Client s3, String bucket, String chave, ArrayNode novosAlertas) throws IOException {
        GetObjectRequest leitura = GetObjectRequest.builder().bucket(bucket).key(chave).build();

        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS; tentativa++) {
            ObjectNode documento;
            String etag = null;
            try {
                ResponseBytes<GetObjectResponse> objeto = s3.getObjectAsBytes(leitura);
                etag = objeto.response().eTag();
                if (etag == null || etag.isBlank()) {
                    throw new IOException("O S3 não retornou o ETag do histórico.");
                }
                documento = lerDocumento(objeto.asByteArray());
            } catch (S3Exception e) {
                if (e.statusCode() != 404) {
                    throw e;
                }
                documento = JACKSON.createObjectNode();
                documento.putArray("alertas");
            }

            ArrayNode historico = (ArrayNode) documento.get("alertas");
            int quantidadeAnterior = historico.size();
            for (int indice = 0; indice < novosAlertas.size(); indice++) {
                JsonNode alerta = novosAlertas.get(indice);
                // Preserva ocorrências idênticas do mesmo lote, sem adicioná-las novamente em um reprocessamento.
                int ocorrenciasNoLote = contarOcorrencias(novosAlertas, indice + 1, alerta);
                if (contarOcorrencias(historico, quantidadeAnterior, alerta) < ocorrenciasNoLote) {
                    historico.add(alerta);
                }
            }
            if (etag != null && historico.size() == quantidadeAnterior) {
                return;
            }

            AwsRequestOverrideConfiguration.Builder condicao = AwsRequestOverrideConfiguration.builder();
            if (etag == null) {
                condicao.putHeader("If-None-Match", "*");
            } else {
                condicao.putHeader("If-Match", etag);
            }
            PutObjectRequest escrita = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(chave)
                    .contentType("application/json")
                    .overrideConfiguration(condicao.build())
                    .build();
            byte[] json = JACKSON.writeValueAsBytes(documento);

            try {
                s3.putObject(escrita, RequestBody.fromBytes(json));
                return;
            } catch (S3Exception e) {
                if (e.statusCode() != 409 && e.statusCode() != 412) {
                    throw e;
                }
                if (tentativa == MAX_TENTATIVAS) {
                    throw new IOException("Conflito ao gravar o histórico após " + MAX_TENTATIVAS + " tentativas.", e);
                }
            }
        }
    }

    private static int contarOcorrencias(ArrayNode alertas, int limite, JsonNode procurado) {
        int ocorrencias = 0;
        for (int indice = 0; indice < limite; indice++) {
            JsonNode alerta = alertas.get(indice);
            if (alerta.get("id").asText().equals(procurado.get("id").asText())
                    && alerta.get("hostname").equals(procurado.get("hostname"))
                    && alerta.get("componente").equals(procurado.get("componente"))
                    && alerta.get("valor").decimalValue().compareTo(procurado.get("valor").decimalValue()) == 0
                    && alerta.get("limite_min").decimalValue().compareTo(procurado.get("limite_min").decimalValue()) == 0
                    && alerta.get("limite_max").decimalValue().compareTo(procurado.get("limite_max").decimalValue()) == 0
                    && alerta.get("data_hora").equals(procurado.get("data_hora"))) {
                ocorrencias++;
            }
        }
        return ocorrencias;
    }

    private static ObjectNode lerDocumento(byte[] json) throws IOException {
        JsonNode documento = JACKSON.readTree(json);
        if (documento == null || !documento.isObject() || documento.size() != 1
                || !documento.path("alertas").isArray()) {
            throw new IOException("JSON existente inválido. Esperado um objeto com o array alertas.");
        }
        for (JsonNode alerta : documento.get("alertas")) {
            if (!alerta.isObject() || alerta.size() != 8
                    || (!alerta.path("id").isTextual() && !alerta.path("id").isIntegralNumber())
                    || !alerta.path("hostname").isTextual()
                    || !alerta.path("componente").isTextual()
                    || !alerta.path("valor").isNumber()
                    || !alerta.path("limite_min").isNumber()
                    || !alerta.path("limite_max").isNumber()
                    || !alerta.path("data_hora").isTextual()
                    || !alerta.path("empresa").isTextual()) {
                throw new IOException("JSON existente inválido. Um alerta não corresponde à estrutura esperada.");
            }
            try {
                OffsetDateTime.parse(alerta.get("data_hora").asText());
            } catch (DateTimeParseException e) {
                throw new IOException("JSON existente inválido. Campo data_hora sem data e fuso válidos.", e);
            }
        }
        return (ObjectNode) documento;
    }
}
