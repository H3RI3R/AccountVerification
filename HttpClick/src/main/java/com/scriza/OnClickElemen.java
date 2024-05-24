package com.scriza;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.nativekey.AndroidKey;
import io.appium.java_client.android.nativekey.KeyEvent;
import io.appium.java_client.android.options.UiAutomator2Options;

public class OnClickElemen extends HttpServlet {
	private static final long serialVersionUID = 1L;
    private static final BlockingQueue<BankTask> taskQueue = new LinkedBlockingQueue<>();
    private static final ExecutorService executorService = Executors.newFixedThreadPool(2);

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String bankName = request.getParameter("t1");
        String accountNumber = request.getParameter("accountNumber");

        if (bankName == null || bankName.isEmpty() || accountNumber == null || accountNumber.isEmpty()) {
            response.getWriter().write("Bank name and account number parameters are required");
            return;
        }

        BankTask task = new BankTask(bankName, accountNumber);
        try {
            taskQueue.put(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Future<String> result = executorService.submit(task);

        try {
            String taskResult = result.get();
            response.getWriter().write(taskResult);
        } catch (InterruptedException | ExecutionException e) {
            response.getWriter().write("Exception occurred: " + e.getMessage());
        }
    }

    private static class BankTask implements Callable<String> {
        private String bankName;
        private String accountNumber;

        public BankTask(String bankName, String accountNumber) {
            this.bankName = bankName;
            this.accountNumber = accountNumber;
        }

        @Override
        public String call() throws Exception {
            AndroidDriver driver = null;

            UiAutomator2Options options1 = new UiAutomator2Options()
                .setDeviceName("android 29 w2")
                .setUdid("emulator-5556")
                .setPlatformName("Android")
                .setPlatformVersion("10");
            int port1 = 472;

            UiAutomator2Options options2 = new UiAutomator2Options()
                .setDeviceName("android 29")
                .setUdid("emulator-5554")
                .setPlatformName("Android")
                .setPlatformVersion("10");
            int port2 = 4723;

            // Decide which options to use based on availability
            // For simplicity, let's alternate between the two options
            boolean useFirstWorker = (taskQueue.size() % 2 == 0);
            UiAutomator2Options options = useFirstWorker ? options1 : options2;
            int port = useFirstWorker ? port1 : port2;

            try {
                driver = new AndroidDriver(new URL("http://127.0.0.1:" + port + "/wd/hub"), options);

                // Your task processing logic here
                driver.findElement(By.xpath("//*[contains(@text, 'search bank')]")).click();
                driver.findElement(By.xpath("//android.widget.EditText")).sendKeys(bankName);

                Thread.sleep(2000);
                driver.pressKey(new KeyEvent(AndroidKey.TAB));
                driver.pressKey(new KeyEvent(AndroidKey.TAB));
                driver.pressKey(new KeyEvent(AndroidKey.ENTER));
                Thread.sleep(5000);
                driver.switchTo().activeElement().sendKeys(accountNumber);

                WebElement bankNameElement = driver.findElement(By.xpath("//android.widget.TextView[@bounds='[116,377][965,423]']"));
                String extractedBankName = bankNameElement.getText();
                System.out.println("Extracted Bank Name:" + extractedBankName);

                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                WebElement continueButton = driver.findElement(By.xpath("//*[contains(@text, 'CONTINUE')]"));
                continueButton.click();

                boolean isBankIFSCTextPresent = driver.findElements(By.xpath("//android.widget.TextView[@text='bank IFSC']")).size() > 0;
                if (isBankIFSCTextPresent) {
                    String ifscCode = getIFSCCodeFromDB(extractedBankName);
                    if (ifscCode != null) {
                        WebElement ifscInputElement = driver.findElement(By.xpath("//android.widget.EditText[@bounds='[113,814][967,940]']"));
                        ifscInputElement.sendKeys(ifscCode + "0000001");

                        continueButton.click();

                        boolean receiverName = driver.findElement(By.xpath("//android.widget.TextView[@text='receiver name']")) != null;
                        if (receiverName) {
                            driver.pressKey(new KeyEvent(AndroidKey.BACK));
                            String retryUrl = "http://localhost:8080/HttpClick/OnClickElemen?t1=(Bank Name)&accountNumber=(Account Number)";
                            return "Error retrieving the userName. Please provide details with another bank. Retry here: " + retryUrl;
                        }
                    }
                } else {
                    WebElement userNameElement = wait.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//*[@content-desc='bene-name_text']")));
                    String userNameText = userNameElement.getText();
                    updateUsernameInBankAccounts(userNameText, accountNumber);
                    driver.pressKey(new KeyEvent(AndroidKey.BACK));
                    driver.pressKey(new KeyEvent(AndroidKey.BACK));
                    return "Username: " + userNameText;
                }
            } catch (MalformedURLException e) {
                return "Malformed URL: " + e.getMessage();
            } catch (Exception e) {
                return "Exception occurred: " + e.getMessage();
            } finally {
                if (driver != null) {
                    driver.quit();
                }
            }

            return "Task completed";
        }

        private void updateUsernameInBankAccounts(String username, String accountNumber) {
            String query = "UPDATE bankaccounts SET username = ? WHERE accountNumber = ?";

            try (Connection connection = DBConnectionManager.getConnection();
                 PreparedStatement preparedStatement = connection.prepareStatement(query)) {

                preparedStatement.setString(1, username);
                preparedStatement.setString(2, accountNumber);

                int rowsAffected = preparedStatement.executeUpdate();
                if (rowsAffected > 0) {
                    System.out.println("Successfully updated username for account number: " + accountNumber);
                } else {
                    System.out.println("No account found with account number: " + accountNumber);
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        private String getIFSCCodeFromDB(String bankName) {
            String ifscCode = null;
            String query = "SELECT ifsc FROM listofbanks WHERE bank_name = ?";

            try (Connection connection = DBConnectionManager.getConnection();
                 PreparedStatement preparedStatement = connection.prepareStatement(query)) {

                preparedStatement.setString(1, bankName);
                ResultSet resultSet = preparedStatement.executeQuery();

                if (resultSet.next()) {
                    ifscCode = resultSet.getString("ifsc");
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return ifscCode;
        }
    }

    public void init() throws ServletException {
        // Nothing to do here as we initialize ExecutorService statically
    }

    @Override
    public void destroy() {
        executorService.shutdown();
    }

    private static class DBConnectionManager {
    	public static Connection getConnection() throws SQLException {
            String url = "jdbc:mysql://localhost:3306/bankdb";
            String username = "root";
            String password = "root";

            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
                return DriverManager.getConnection(url, username, password);
            } catch (ClassNotFoundException e) {
                throw new SQLException("MySQL JDBC driver not found", e);
            }
        }
    }
}
