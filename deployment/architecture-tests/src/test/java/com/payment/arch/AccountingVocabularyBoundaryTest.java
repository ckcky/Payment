package com.payment.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spec 031 §7.7 / SC-3 门禁：记账**决策词汇**（科目 code 常量、direction 字面量）
 * 只允许出现在 ledger 实现与事件契约枚举（common-dto {@code rpc} 包）中——
 * 上游调用方（payment / settlement / reconciliation / order …）源码里出现
 * {@code "MERCHANT_PAYABLE"}、{@code "DEBIT"} 之类的字符串字面量，即宣告「科目/方向决策权
 * 扩散回调用方」，构建必须失败。这把「决策权收口」从评审约定变成机器强制。
 *
 * <p><b>为什么是源码扫描而不是 ArchUnit 字节码规则</b>：本门禁约束的对象是<b>字符串常量</b>，
 * 而 ArchUnit 的依赖模型只看类/方法/字段引用，不遍历常量池（字面量 {@code "DEBIT"} 不产生任何
 * 类依赖，写不出可命中的 noClasses 规则）。引用 {@code LedgerDirection.DEBIT.name()} 是合规的
 * 契约枚举用法，源码上不含带引号字面量——扫描口径与 §7.7 语义精确对齐。
 * 与 {@link ServiceBoundaryTest} 同为「读构建仓库源码、零 classpath 耦合」的构建期门禁。</p>
 *
 * <p><b>防空转</b>：先断言实际扫描到的文件数（目录定位失效时扫描 0 个文件会假绿）。</p>
 */
class AccountingVocabularyBoundaryTest {

    /** 科目 code（AccountCode 枚举全集，spec 031 §5.1）。 */
    private static final String[] ACCOUNT_CODES = {
            "CUSTOMER_CASH", "MERCHANT_PAYABLE", "SETTLEMENT_PAYABLE", "FEE_REVENUE",
            "SUSPENSE", "BANK_CASH", "CHANNEL_RECEIVABLE", "CHANNEL_FEE_EXPENSE"};

    /** 分录方向字面量（direction 决策属 ledger，调用方只读回显须走 LedgerDirection）。 */
    private static final String[] DIRECTIONS = {"DEBIT", "CREDIT"};

    /** 豁免：ledger 实现（决策权所在地）与事件契约枚举包（§7.7 明文允许）。 */
    private static final List<Path> EXEMPT = List.of(
            Path.of("ledger-service"),
            Path.of("common", "common-dto", "src", "main", "java", "com", "payment", "common", "dto", "rpc"));

    @Test
    void accountCodeAndDirectionLiteralsMustBeConfinedToLedgerAndContractEnums() throws IOException {
        Path repoRoot = Path.of("..", "..").toAbsolutePath().normalize();
        List<String> tokens = new ArrayList<>();
        for (String code : ACCOUNT_CODES) {
            tokens.add('"' + code + '"');
        }
        for (String dir : DIRECTIONS) {
            tokens.add('"' + dir + '"');
        }
        Pattern literal = Pattern.compile("(" + String.join("|", tokens.stream().map(Pattern::quote).toList()) + ")");

        List<String> violations = new ArrayList<>();
        int scannedFiles = 0;
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            List<Path> mainSources = walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("src" + java.io.File.separator + "main"
                            + java.io.File.separator + "java"))
                    // 只检各 Maven 模块源码（deployment 下的 e2e/architecture 脚本夹具不在口径内）
                    .filter(p -> isModuleSource(repoRoot, p))
                    .filter(p -> !isExempt(repoRoot, p))
                    .toList();
            for (Path file : mainSources) {
                scannedFiles++;
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = literal.matcher(lines.get(i));
                    if (m.find()) {
                        violations.add(repoRoot.relativize(file) + ":" + (i + 1) + " → " + m.group());
                    }
                }
            }
        }

        assertThat(scannedFiles)
                .as("扫描到的上游 main 源文件数（0 或过小说明目录定位失效，门禁在空转）").isGreaterThan(100);
        assertThat(violations)
                .as("spec 031 §7.7：科目 code 常量 / direction 字面量禁落 ledger 与契约枚举之外").isEmpty();
    }

    /** 模块源码 = src/main/java 之前只有一层目录（服务在根、common 子模块在 common/ 下一层）。 */
    private boolean isModuleSource(Path repoRoot, Path file) {
        Path relative = repoRoot.relativize(file);
        int srcIndex = -1;
        for (int i = 0; i < relative.getNameCount(); i++) {
            if (relative.getName(i).toString().equals("src")) {
                srcIndex = i;
                break;
            }
        }
        if (srcIndex < 0 || srcIndex > 2) {
            return false; // deployment/architecture-tests 等两层以上的一律不检
        }
        // 排除 deployment/ 与 target/ 之类的非模块路径
        return srcIndex >= 1 && !relative.getName(0).toString().equals("deployment")
                && !relative.toString().contains("target");
    }

    private boolean isExempt(Path repoRoot, Path file) {
        Path relative = repoRoot.relativize(file);
        return EXEMPT.stream().anyMatch(relative::startsWith);
    }
}
