package main.java.controller;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.*;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class WebScraping {
    private static final String TARGET_URL = "https://www.gov.br/ans/pt-br/acesso-a-informacao/participacao-da-sociedade/atualizacao-do-rol-de-procedimentos";
    private static final String DOWNLOAD_DIR = "downloads";
    private static final String ZIP_FILE = "anexos.zip";

    public static void main(String[] args) {
        // Limpa o cache do WebDriverManager antes de começar
        WebDriverManager.chromedriver().clearDriverCache().setup();
        dataScrape();
    }

    private static void dataScrape() {
        WebDriver driver = null;
        try {
            driver = configureWebDriver();

            // 1.1 Acesso ao site
            accessTargetWebsite(driver);

            // 1.2 Download dos Anexos I e II em PDF
            List<String> downloadedFiles = downloadPdfFiles(driver);

            // 1.3 Compactação dos arquivos
            if (!downloadedFiles.isEmpty()) {
                createZipFile(downloadedFiles);
                System.out.println("Arquivos compactados com sucesso em: " + ZIP_FILE);
            } else {
                System.out.println("Nenhum arquivo PDF foi baixado.");
            }

        } catch (Exception e) {
            System.err.println("Erro durante o web scraping: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    private static WebDriver configureWebDriver() {
        // Configuração mais robusta do WebDriverManager
        WebDriverManager.chromedriver().clearResolutionCache().setup();

        ChromeOptions options = new ChromeOptions();
        // Configurações otimizadas para evitar problemas
        options.addArguments(
                "--remote-allow-origins=*",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-blink-features=AutomationControlled",
                "--start-maximized",
                "window-size=1200,800",
                "user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36"
        );

        // Configuração para downloads automáticos
        HashMap<String, Object> chromePrefs = new HashMap<>();
        String downloadPath = System.getProperty("user.dir") + File.separator + DOWNLOAD_DIR;
        chromePrefs.put("download.default_directory", downloadPath);
        chromePrefs.put("download.prompt_for_download", false);
        chromePrefs.put("download.directory_upgrade", true);
        chromePrefs.put("safebrowsing.enabled", true);
        options.setExperimentalOption("prefs", chromePrefs);

        // Ignorar erros de certificado SSL (útil para alguns ambientes)
        options.setAcceptInsecureCerts(true);

        return new ChromeDriver(options);
    }

    private static void accessTargetWebsite(WebDriver driver) {
        driver.get(TARGET_URL);

        // Espera mais inteligente com condições mistas
        new WebDriverWait(driver, Duration.ofSeconds(20))
                .until(ExpectedConditions.or(
                        ExpectedConditions.presenceOfElementLocated(By.tagName("body")),
                        ExpectedConditions.presenceOfElementLocated(By.id("content"))
                ));

        // Aceitar cookies - com XPath corrigido e tratamento mais robusto
        try {
            // Tentativa com XPath mais genérico para botão de aceitar cookies
            List<WebElement> acceptButtons = driver.findElements(By.xpath(
                    "//button[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'aceitar') or " +
                            "contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'concordo')]"));

            if (!acceptButtons.isEmpty()) {
                acceptButtons.get(0).click();
                System.out.println("Cookies aceitos com sucesso.");
                // Pequena pausa após aceitar cookies
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            System.out.println("Nenhum botão de cookies encontrado ou erro ao aceitar: " + e.getMessage());
        }
    }

    private static List<String> downloadPdfFiles(WebDriver driver) {
        List<String> downloadedFiles = new ArrayList<>();

        try {
            // Criar diretório de downloads se não existir
            Path downloadPath = Paths.get(DOWNLOAD_DIR);
            if (!Files.exists(downloadPath)) {
                Files.createDirectories(downloadPath);
                System.out.println("Diretório de downloads criado: " + downloadPath.toAbsolutePath());
            }

            // Localizar os links para os Anexos I e II com XPath mais flexível
            List<WebElement> pdfLinks = driver.findElements(By.xpath(
                    "//a[contains(@href, '.pdf')][" +
                            "contains(translate(., 'ABCDEFGHIJKLMNOPQRSTUVWXYZÁÉÍÓÚÀÈÌÒÙÂÊÎÔÛÃÕÇ', 'abcdefghijklmnopqrstuvwxyzáéíóúàèìòùâêîôûãõç'), 'anexo i') or " +
                            "contains(translate(., 'ABCDEFGHIJKLMNOPQRSTUVWXYZÁÉÍÓÚÀÈÌÒÙÂÊÎÔÛÃÕÇ', 'abcdefghijklmnopqrstuvwxyzáéíóúàèìòùâêîôûãõç'), 'anexo ii')]"));

            if (pdfLinks.isEmpty()) {
                System.out.println("Nenhum link de PDF encontrado na página.");
                return downloadedFiles;
            }

            System.out.println("Encontrados " + pdfLinks.size() + " links de PDF relevantes.");

            for (WebElement link : pdfLinks) {
                try {
                    String fileUrl = link.getAttribute("href");
                    String fileName = fileUrl.substring(fileUrl.lastIndexOf('/') + 1);
                    String filePath = DOWNLOAD_DIR + File.separator + fileName;

                    System.out.println("Iniciando download de: " + fileName);
                    downloadFile(fileUrl, filePath);

                    // Verifica se o arquivo foi baixado corretamente
                    if (Files.exists(Paths.get(filePath)) && Files.size(Paths.get(filePath)) > 0) {
                        downloadedFiles.add(filePath);
                        System.out.println("Download concluído: " + fileName);
                    } else {
                        System.err.println("Falha no download do arquivo: " + fileName);
                    }
                } catch (Exception e) {
                    System.err.println("Erro ao baixar arquivo: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            System.err.println("Erro ao processar links de PDF: " + e.getMessage());
            e.printStackTrace();
        }

        return downloadedFiles;
    }

    private static void downloadFile(String fileUrl, String destinationPath) throws IOException {
        URL url = new URL(fileUrl);
        try (InputStream in = url.openStream();
             ReadableByteChannel rbc = Channels.newChannel(in);
             FileOutputStream fos = new FileOutputStream(destinationPath)) {
            fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
        }
    }

    private static void createZipFile(List<String> filesToZip) {
        if (filesToZip == null || filesToZip.isEmpty()) {
            System.out.println("Nenhum arquivo para compactar.");
            return;
        }

        try (FileOutputStream fos = new FileOutputStream(ZIP_FILE);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            for (String filePath : filesToZip) {
                Path path = Paths.get(filePath);
                if (!Files.exists(path)) {
                    System.err.println("Arquivo não encontrado: " + filePath);
                    continue;
                }

                try (FileInputStream fis = new FileInputStream(filePath)) {
                    ZipEntry zipEntry = new ZipEntry(path.getFileName().toString());
                    zos.putNextEntry(zipEntry);

                    byte[] bytes = new byte[1024];
                    int length;
                    while ((length = fis.read(bytes)) >= 0) {
                        zos.write(bytes, 0, length);
                    }
                    zos.closeEntry();
                    System.out.println("Arquivo adicionado ao ZIP: " + path.getFileName());
                }
            }
        } catch (IOException e) {
            System.err.println("Erro ao criar arquivo ZIP: " + e.getMessage());
            e.printStackTrace();
        }
    }
}