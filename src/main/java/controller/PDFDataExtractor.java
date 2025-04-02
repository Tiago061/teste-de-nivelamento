package main.java.controller;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class PDFDataExtractor {
    private static final String PDF_PATH = "downloads/Anexo_I_Rol_2021RN_465.2021_RN627L.2024.pdf";
    private static final String CSV_PATH = "Rol_Procedimentos.csv";
    private static final String ZIP_PATH = "Teste_Henrique.zip";

    public static void main(String[] args) {
        try {
            // 1. Extrair dados da tabela
            System.out.println("Iniciando extração de dados...");
            List<String[]> tableData = extractTableDataFromPDF();

            if (tableData.isEmpty()) {
                System.out.println("Nenhum dado foi extraído do PDF.");
                return;
            }

            // 2. Processar abreviações
            System.out.println("Processando abreviações...");
            replaceAbbreviations(tableData);

            // 3. Gerar arquivo CSV
            System.out.println("Gerando arquivo CSV...");
            saveToCSV(tableData);

            // 4. Compactar CSV
            System.out.println("Compactando arquivo...");
            List<String> filesToZip = new ArrayList<>();
            filesToZip.add(CSV_PATH);
            createZipFile(filesToZip);

            System.out.println("Processo concluído com sucesso!");
            System.out.println("Arquivo gerado: " + new File(ZIP_PATH).getAbsolutePath());

        } catch (Exception e) {
            System.err.println("Erro durante o processamento: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static List<String[]> extractTableDataFromPDF() throws IOException {
        List<String[]> data = new ArrayList<>();

        try (PDDocument document = PDDocument.load(new File(PDF_PATH))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            stripper.setWordSeparator(" ");
            stripper.setAddMoreFormatting(true);  // Melhora extração de tabelas

            // Processar todas as páginas
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);

                // Extrair dados com padrão específico para este PDF
                extractDataWithCustomPattern(text, data);
            }
        }

        System.out.println("Total de registros extraídos: " + data.size());
        return data;
    }

    private static void extractDataWithCustomPattern(String text, List<String[]> data) {
        String[] lines = text.split("\\r?\\n");
        boolean inTableSection = false;
        boolean headerFound = false;

        // Padrão para linhas de dados (ajustado para este PDF específico)
        Pattern dataPattern = Pattern.compile(
                "^(\\d{4}\\.\\d{2}\\.\\d{2}-\\d)" +  // Código
                        "\\s+(.+?)" +                         // Descrição do procedimento
                        "\\s+(\\d{2}/\\d{2}/\\d{4})" +       // Vigência (data)
                        "\\s+(S|N)" +                         // OD
                        "\\s+(S|N)" +                         // AMB
                        "\\s+(S|N)" +                         // HCO
                        "\\s+(S|N)" +                         // HSO
                        "\\s+(\\d+\\.\\d{2})?" +             // REF (opcional)
                        "\\s+(\\d+\\.\\d{2})?" +             // PAC (opcional)
                        "\\s+(\\w+)");                        // DUT

        for (String line : lines) {
            // Verificar início da tabela
            if (line.contains("Rol de Procedimentos e Eventos em Saúde")) {
                inTableSection = true;
                continue;
            }

            // Verificar cabeçalho da tabela
            if (inTableSection && line.contains("PROCEDIMENTO VIGÊNCIA OD AMB HCO HSO REF PAC DUT")) {
                headerFound = true;
                continue;
            }

            // Verificar fim da tabela
            if (line.contains("Legenda:") || line.contains("Fonte:")) {
                inTableSection = false;
                headerFound = false;
                continue;
            }

            // Processar apenas linhas de dados
            if (inTableSection && headerFound) {
                line = line.trim();

                // Pular linhas vazias ou de seção
                if (line.isEmpty() || line.startsWith("(") || line.matches("^[A-Z]{3,}\\s*$")) {
                    continue;
                }

                // Tentar casar com o padrão
                Matcher matcher = dataPattern.matcher(line);
                if (matcher.find()) {
                    String[] row = new String[4];
                    row[0] = matcher.group(1); // Código
                    row[1] = matcher.group(2); // Descrição
                    row[2] = "";               // Segmento (preencher conforme necessidade)
                    row[3] = (matcher.group(4).equals("S") ? "Odontológico" : "");
                    if (matcher.group(5).equals("S")) {
                        row[3] += (row[3].isEmpty() ? "" : "|") + "Ambulatorial";
                    }
                    data.add(row);

                    System.out.println("Registro extraído: " + String.join(" | ", row));
                } else {
                    System.out.println("Linha não processada: " + line);
                }
            }
        }
    }



    private static void extractTableData(String text, List<String[]> data) {
        String[] lines = text.split("\\r?\\n");
        boolean inTable = false;

        for (String line : lines) {
            // Verificar se é o início da tabela
            if (line.matches(".*\\bCódigo\\b.*\\bDescrição\\b.*\\bSegmento\\b.*")) {
                inTable = true;
                continue;
            }

            // Verificar se é o fim da tabela
            if (line.contains("Legenda:") || line.contains("Fonte:")) {
                inTable = false;
                continue;
            }

            // Processar linhas da tabela
            if (inTable && line.matches("^\\d{4}\\.\\d{2}\\.\\d{2}-\\d.*")) {
                String[] columns = line.split("\\s{2,}");
                if (columns.length >= 4) {
                    // Garantir que temos exatamente 4 colunas
                    String[] row = new String[4];
                    System.arraycopy(columns, 0, row, 0, Math.min(columns.length, 4));
                    data.add(row);
                }
            }
        }
    }

    private static void replaceAbbreviations(List<String[]> data) {
        for (String[] row : data) {
            if (row.length > 3) {
                row[3] = row[3].replace("OD", "Odontológico")
                        .replace("AMB", "Ambulatorial")
                        .replace("HOSP", "Hospitalar")
                        .replace("UTI", "Unidade de Terapia Intensiva");
            }
        }
    }

    private static void saveToCSV(List<String[]> data) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(CSV_PATH));
             CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT
                     .withHeader("Código", "Descrição", "Segmento", "Detalhe do Segmento"))) {

            for (String[] row : data) {
                csvPrinter.printRecord(row[0], row[1], row[2], row[3]);
            }
            csvPrinter.flush();
        }
    }

    private static void createZipFile(List<String> filesToZip) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(ZIP_PATH);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            for (String filePath : filesToZip) {
                File file = new File(filePath);
                if (file.exists()) {
                    try (FileInputStream fis = new FileInputStream(file)) {
                        ZipEntry zipEntry = new ZipEntry(file.getName());
                        zos.putNextEntry(zipEntry);

                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = fis.read(buffer)) > 0) {
                            zos.write(buffer, 0, length);
                        }
                        zos.closeEntry();
                    }
                }
            }
        }
    }
}