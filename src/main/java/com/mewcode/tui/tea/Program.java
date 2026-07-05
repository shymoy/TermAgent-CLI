
package com.mewcode.tui.tea;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 基于 JLine 的 Bubble Tea 风格 TUI 运行时。
 *
 * 内联渲染（与 Bubble Tea 完全一致）：
 *  - view 从当前光标位置开始画，不用 \033[H]，不破坏之前的终端内容
 *  - 重绘时 cursor up 回到 view 起始行覆写
 *  - println 清除 view 后写文本，文本留在终端 scrollback
 *  - linesRendered 跟踪 view 实际占用的终端行数，包括终端自动折行
 */
public class Program {

    private final Model model;
    private final BlockingQueue<Message> msgQueue = new LinkedBlockingQueue<>();
    private Terminal terminal;
    private PrintWriter writer;
    private volatile boolean running;

    // 从 view 最后一行向上移动这么多终端行，可以回到 view 第一行。
    private int linesRendered;
    private String lastViewContent = "";

    public Program(Model model) {
        this.model = model;
    }

    public void send(Message msg) {
        msgQueue.offer(msg);
    }

    public int getAvailableHeight() {
        int h = terminal != null ? terminal.getSize().getRows() : 24;
        return Math.max(h - 1, 3);
    }

    public void run() {
        try {
            terminal = TerminalBuilder.builder().system(true).build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to open terminal: " + e.getMessage(), e);
        }

        terminal.enterRawMode();
        writer = terminal.writer();
        writer.flush();

        running = true;

        terminal.handle(Terminal.Signal.INT, sig ->
                msgQueue.offer(new KeyPressMessage("ctrl+c", null)));
        terminal.handle(Terminal.Signal.WINCH, sig -> {
            var size = terminal.getSize();
            msgQueue.offer(new WindowSizeMessage(size.getColumns(), size.getRows()));
        });

        Thread.startVirtualThread(this::keyReaderLoop);
        executeCommand(model.init());
        renderView();

        try {
            while (running) {
                Message msg = msgQueue.poll(16, TimeUnit.MILLISECONDS);
                if (msg == null) continue;
                if (msg instanceof QuitMessage) { running = false; break; }

                var result = model.update(msg);
                if (result.command() != null) executeCommand(result.command());
                renderView();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running = false;
            // 清掉 view（println 已在 scrollback 里了）
            clearView();
            writer.print("\033[?25h");
            writer.flush();
            try { terminal.close(); } catch (IOException ignored) {}
        }
    }

    // ── 内联渲染（Bubble Tea 方式）────────────────────────────────────

    private void renderView() {
        String view = model.view();
        if (view.equals(lastViewContent)) return;
        lastViewContent = view;

        // 去掉末尾换行（view 不以 \n 结尾，cursor 留在最后一行末尾）
        if (view.endsWith("\n")) {
            view = view.substring(0, view.length() - 1);
        }

        // cursor up 回到 view 起始行
        if (linesRendered > 0) {
            writer.print("\033[" + linesRendered + "A");
        }
        writer.print("\r");

        // 逐行写入，每行末尾 \033[K 清除残余字符
        String[] lines = view.split("\n", -1);
        var sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i]).append("\033[K");
            if (i < lines.length - 1) sb.append("\n");
        }
        writer.print(sb);
        // 清除多余行
        writer.print("\033[J");

        linesRendered = renderedLineBreaks(view, terminal.getSize().getColumns());

        writer.flush();
    }

    /**
     * 计算光标从 view 首行移动到末行经过的终端行数。
     * ANSI 样式不占列宽，中文等宽字符则可能占两列；超过终端宽度的内容会自动折行。
     */
    static int renderedLineBreaks(String view, int terminalWidth) {
        int width = Math.max(terminalWidth, 1);
        String[] lines = view.split("\\n", -1);
        int breaks = lines.length - 1;
        for (String line : lines) {
            int columns = AttributedString.fromAnsi(line).columnLength();
            if (columns > 0) {
                // 恰好写到末列不会立即换行；下一个可见字符才触发自动折行。
                breaks += (columns - 1) / width;
            }
        }
        return breaks;
    }

    // 清除当前 view 区域
    private void clearView() {
        if (linesRendered > 0) {
            writer.print("\033[" + linesRendered + "A");
        }
        writer.print("\r\033[J");
        linesRendered = 0;
        lastViewContent = "";
        writer.flush();
    }

    // ── 命令执行 ────────────────────────────────────────────────────────

    private void executeCommand(Command cmd) {
        if (cmd == null) return;
        switch (cmd) {
            case Command.Simple s -> {
                Message msg = s.fn().get();
                if (msg != null) msgQueue.offer(msg);
            }
            case Command.Tick t -> {
                Thread.startVirtualThread(() -> {
                    try { Thread.sleep(t.delay().toMillis()); }
                    catch (InterruptedException e) { return; }
                    if (!running) return;
                    Message msg = t.fn().apply(Instant.now());
                    if (msg != null) msgQueue.offer(msg);
                });
            }
            case Command.CheckWindowSize ignored -> {
                var size = terminal.getSize();
                msgQueue.offer(new WindowSizeMessage(size.getColumns(), size.getRows()));
            }
            case Command.Batch b -> {
                for (var c : b.commands()) executeCommand(c);
            }
            case Command.PrintLine p -> {
                // 清除 view，写 println 文本（留在终端 scrollback），重绘 view
                clearView();
                writer.print(p.text() + "\n");
                writer.flush();
                renderView();
            }
        }
    }

    // ── 按键读取 ────────────────────────────────────────────────────────

    private void keyReaderLoop() {
        NonBlockingReader reader = terminal.reader();
        try {
            while (running) {
                int c = reader.read(50);
                if (c == -2) continue;
                if (c == -1) { msgQueue.offer(new QuitMessage()); return; }
                Message msg = parseInput(c, reader);
                if (msg != null) msgQueue.offer(msg);
            }
        } catch (IOException e) {
            if (running) msgQueue.offer(new QuitMessage());
        }
    }

    private Message parseInput(int c, NonBlockingReader reader) throws IOException {
        if (c == 0x1B) {
            int next = reader.peek(80);
            if (next == '[') { reader.read(); return parseCSI(reader); }
            return key("escape");
        }
        if (c == 0x0D || c == 0x0A) return key("enter");
        if (c == 0x09) return key("tab");
        if (c == 0x03) return key("ctrl+c");
        if (c == 0x08) return key("ctrl+h");
        if (c == 0x0F) return key("ctrl+o");
        if (c >= 1 && c <= 26) return key("ctrl+" + (char) ('a' + c - 1));
        if (c == 0x7F) return key("backspace");
        if (c == ' ') return new KeyPressMessage(" ", new char[]{' '});
        if (c >= 32) {
            char[] chars = Character.toChars(c);
            return new KeyPressMessage(new String(chars), chars);
        }
        return null;
    }

    private Message parseCSI(NonBlockingReader reader) throws IOException {
        var buf = new StringBuilder();
        while (true) {
            int ch = reader.read(80);
            if (ch == -2 || ch == -1) break;
            buf.append((char) ch);
            if (ch >= 0x40 && ch <= 0x7E) break;
        }
        String seq = buf.toString();
        if (seq.isEmpty()) return key("escape");
        char fin = seq.charAt(seq.length() - 1);
        String params = seq.substring(0, seq.length() - 1);
        return switch (fin) {
            case 'A' -> key("up");
            case 'B' -> key("down");
            case 'C' -> key("right");
            case 'D' -> key("left");
            case 'H' -> key("home");
            case 'F' -> key("end");
            case 'Z' -> key("shift+tab");
            case '~' -> switch (params) {
                case "5" -> key("pgup"); case "6" -> key("pgdown");
                case "1","7" -> key("home"); case "4","8" -> key("end");
                default -> null;
            };
            case 'M','m' -> parseSGRMouse(params);
            default -> null;
        };
    }

    private Message parseSGRMouse(String params) {
        if (!params.startsWith("<")) return null;
        String[] parts = params.substring(1).split(";");
        if (parts.length < 3) return null;
        try {
            int btn = Integer.parseInt(parts[0]);
            if (btn == 64) return new MouseMessage(MouseMessage.MouseButton.MouseButtonWheelUp);
            if (btn == 65) return new MouseMessage(MouseMessage.MouseButton.MouseButtonWheelDown);
            return new MouseMessage(MouseMessage.MouseButton.OTHER);
        } catch (NumberFormatException e) { return null; }
    }

    private static KeyPressMessage key(String name) {
        return new KeyPressMessage(name, null);
    }
}
