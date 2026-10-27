package com.tpp.simulator.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Proxy TCP tối giản dùng để mô phỏng PostgreSQL "tạm mất" rồi "trở lại" mà không cần dừng
 * container: {@link #stop()} đóng cổng và mọi kết nối đang chạy, {@link #start()} mở lại đúng cổng
 * cũ.
 */
public final class TcpProxy implements AutoCloseable {

  private final String targetHost;
  private final int targetPort;
  private final List<Socket> sockets = new CopyOnWriteArrayList<>();
  private volatile ServerSocket server;
  private int port;

  public TcpProxy(String targetHost, int targetPort) {
    this.targetHost = targetHost;
    this.targetPort = targetPort;
  }

  public synchronized void start() throws IOException {
    ServerSocket socket = new ServerSocket();
    socket.setReuseAddress(true);
    socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    port = socket.getLocalPort();
    server = socket;
    Thread acceptor = new Thread(() -> acceptLoop(socket), "tcp-proxy-acceptor");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  public int port() {
    return port;
  }

  public synchronized void stop() {
    ServerSocket socket = server;
    server = null;
    close(socket);
    for (Socket s : sockets) {
      close(s);
    }
    sockets.clear();
  }

  @Override
  public void close() {
    stop();
  }

  private void acceptLoop(ServerSocket socket) {
    while (!socket.isClosed()) {
      try {
        Socket client = socket.accept();
        Socket upstream = new Socket(targetHost, targetPort);
        sockets.add(client);
        sockets.add(upstream);
        pump(client, upstream);
        pump(upstream, client);
      } catch (IOException e) {
        if (socket.isClosed()) {
          return;
        }
      }
    }
  }

  private void pump(Socket from, Socket to) {
    Thread thread =
        new Thread(
            () -> {
              try (InputStream in = from.getInputStream();
                  OutputStream out = to.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                  out.write(buffer, 0, read);
                  out.flush();
                }
              } catch (IOException ignored) {
                // Kết nối bị đóng: bình thường khi proxy dừng.
              } finally {
                close(from);
                close(to);
              }
            },
            "tcp-proxy-pump");
    thread.setDaemon(true);
    thread.start();
  }

  private static void close(AutoCloseable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (Exception ignored) {
      // Bỏ qua lỗi khi đóng.
    }
  }
}
