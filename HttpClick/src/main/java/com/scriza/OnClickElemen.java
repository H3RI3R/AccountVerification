package com.scriza;

import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.nativekey.AndroidKey;
import io.appium.java_client.android.nativekey.KeyEvent;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.MalformedURLException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;


public class OnClickElemen extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final BlockingQueue<BankTask> taskQueue = new LinkedBlockingQueue<>();
    private static final ExecutorService executorService = Executors.newFixedThreadPool(4);
    private static final String[] DEVICE_UDIDS = {"emulator-5554"};
    private static final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public void init() throws ServletException {
        // Initialization logic if needed
    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String bankName = request.getParameter("t1");
        String accountNumber = request.getParameter("accountNumber");

        if (bankName == null || bankName.isEmpty() || accountNumber == null || accountNumber.isEmpty()) {
            response.getWriter().write("Bank name and account number parameters are required");
            return;
        }

        BankTask task = new BankTask(bankName, accountNumber, getNextUdid());
        try {
            taskQueue.put(task);
        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
        }

        Future<String> result = executorService.submit(task);

        try {
            String taskResult = result.get();
            response.getWriter().write(taskResult);
        } catch (InterruptedException | ExecutionException e) {
            response.getWriter().write("Exception occurred: " + e.getMessage());
        }
    }

    @Override
    public void destroy() {
        executorService.shutdown();
        AndroidDriverManager.quitAllDrivers();
    }

    private String getNextUdid() {
        int index = counter.getAndIncrement() % DEVICE_UDIDS.length;
        return DEVICE_UDIDS[index];
    }

    private static class BankTask implements Callable<String> {
        private final String bankName;
        private final String accountNumber;
        private final String udid;

        public BankTask(String bankName, String accountNumber, String udid) {
            this.bankName = bankName;
            this.accountNumber = accountNumber;
            this.udid = udid;
        }

        @Override
        public String call() throws Exception {
            AndroidDriver driver;
            try {
                driver = AndroidDriverManager.getDriver(udid);
            } catch (MalformedURLException e) {
                return "Failed to connect to Appium server: " + e.getMessage();
            }

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

            try {
                storeInDatabase(bankName, accountNumber);

                WebElement searchBankElement = driver.findElement(By.xpath("//*[contains(@text, 'search bank')]"));
                searchBankElement.sendKeys(bankName);
                Thread.sleep(1000);
                driver.pressKey(new KeyEvent(AndroidKey.TAB));
                driver.pressKey(new KeyEvent(AndroidKey.TAB));
                driver.pressKey(new KeyEvent(AndroidKey.DPAD_DOWN));
                driver.pressKey(new KeyEvent(AndroidKey.ENTER));
                Thread.sleep(3000);
                driver.switchTo().activeElement().sendKeys(accountNumber);

                WebElement bankNameElement = wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath("//android.widget.TextView[@bounds='[116,377][965,423]']")));
                String extractedBankName = bankNameElement.getText();

                WebElement continueButton = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//*[contains(@text, 'CONTINUE')]")));
                continueButton.click();

                boolean isBankIFSCTextPresent = !driver.findElements(By.xpath("//android.widget.TextView[@text='bank IFSC']")).isEmpty();
                if (isBankIFSCTextPresent) {
                    String ifscCode = getIFSCCodeFromDB(extractedBankName);
                    if (ifscCode != null) {
                        WebElement ifscInputElement = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//android.widget.EditText[@bounds='[113,814][967,940]']")));
                        String ifscCodeLatest = ifscCode + "0000010";
                        ifscInputElement.sendKeys(ifscCodeLatest);
                        continueButton.click();

                        boolean receiverNamePresent = !driver.findElements(By.xpath("//android.widget.TextView[@text='receiver name']")).isEmpty();
                        if (receiverNamePresent) {
                            driver.pressKey(new KeyEvent(AndroidKey.BACK));
                            driver.pressKey(new KeyEvent(AndroidKey.BACK));
                            return "Error retrieving the userName. Please provide details with another bank. Retry here: " + getRetryUrl();
                        }
                    }
                } else {
                    WebElement userNameElement = driver.findElement(By.xpath("//*[@content-desc='bene-name_text']"));
                    String userNameText = userNameElement.getText();
                    updateUsernameInBankAccounts(userNameText, accountNumber);
                    driver.pressKey(new KeyEvent(AndroidKey.BACK));
                    driver.pressKey(new KeyEvent(AndroidKey.BACK));
                    driver.pressKey(new KeyEvent(AndroidKey.BACK));
                    return "Username: " + userNameText;
                }
            } catch (Exception e) {
                driver.pressKey(new KeyEvent(AndroidKey.BACK));
                driver.pressKey(new KeyEvent(AndroidKey.BACK));
                return "Error retrieving the userName. Please provide details with another bank. Retry here: " + getRetryUrl();
            }

            return "Task completed";
        }

        private void storeInDatabase(String bankName, String accountNumber) {
            String query = "INSERT INTO bankaccounts (bankName, accountNumber) VALUES (?, ?)";
            try (Connection connection = DBConnectionManager.getConnection();
                 PreparedStatement preparedStatement = connection.prepareStatement(query)) {
                preparedStatement.setString(1, bankName);
                preparedStatement.setString(2, accountNumber);
                preparedStatement.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
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

        private String getRetryUrl() {
            return "http://159.69.58.227:8080/HttpClick/OnClickElemen?t1=(Bank Name)&accountNumber=(Account Number)";
        }
    }
}
