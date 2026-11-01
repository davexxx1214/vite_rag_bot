package com.example.ssbotcli;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class WindowsIntegrationTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();
  @BeforeEach void windows() { org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows")); }
  @Test void realDiscoveryInspectionPortsHostAndEventsReturnStructuredFacts() throws Exception {
    var runner = new PowerShellRunner(json);
    var found = runner.run(HostScripts.DISCOVER, Map.of(), new RunControl(60));
    assertTrue(found.path("services").isArray(), found.toString()); assertTrue(found.path("total").asInt() > 0);
    var service = runner.run(HostScripts.SERVICE, Map.of("name", "EventLog"), new RunControl(60));
    assertEquals("EventLog", service.path("service").path("Name").asString(), service.toString());
    var host = runner.run(HostScripts.HOST, Map.of(), new RunControl(60));
    assertTrue(host.path("disks").isArray(), host.toString());
    var ports = runner.run(HostScripts.PORTS, Map.of("ports", java.util.List.of(65534)), new RunControl(60));
    assertTrue(ports.path("listeners").isArray(), ports.toString());
    var events = runner.run(HostScripts.EVENTS, Map.of("providers", java.util.List.of("Service Control Manager"), "minutes", 30, "limit", 3), new RunControl(60));
    assertTrue(events.path("events").isArray(), events.toString()); assertTrue(events.has("issues"));
  }

  @Test void endpointsReportHttpRedirectTcpAndFailuresWithoutFollowingRedirects() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger redirected = new AtomicInteger();
    server.createContext("/ok", e -> { e.sendResponseHeaders(200,-1); e.close(); });
    server.createContext("/redirect", e -> { e.getResponseHeaders().add("Location", "/secret"); e.sendResponseHeaders(302,-1); e.close(); });
    server.createContext("/secret", e -> { redirected.incrementAndGet(); e.sendResponseHeaders(200,-1); e.close(); });
    server.start();
    try {
      int port = server.getAddress().getPort();
      Path file = temp.resolve("services.yaml");
      Files.writeString(file, "services:\n  - id: local\n    type: windows-service\n    service_name: EventLog\n"
          + "    health: http://127.0.0.1:"+port+"/ok\n    depends_on: [http://127.0.0.1:"+port+"/redirect, tcp://127.0.0.1:"+port+"]\n");
      var tool = new WindowsToolPack(ServiceCatalog.load(file,true),json).tools().stream()
          .filter(t -> t.name().equals("check_service_endpoints")).findFirst().orElseThrow();
      var result = tool.execute(json.valueToTree(Map.of("service_id","local")),new RunControl(60));
      assertEquals(3,result.path("checks").size(),result.toString());
      assertEquals(200,result.path("checks").get(0).path("status").asInt());
      assertEquals(302,result.path("checks").get(1).path("status").asInt());
      assertTrue(result.path("checks").get(2).path("reachable").asBoolean()); assertEquals(0,redirected.get());
    } finally { server.stop(0); }
  }

  @Test void powerShellCancellationAndOutputLimitsTerminateWork() throws Exception {
    var runner = new PowerShellRunner(json); RunControl control = new RunControl(60);
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var work = pool.submit(() -> runner.run("Start-Sleep -Seconds 30; @{ok=$true}", Map.of(), control));
      // Wait until the worker has started before issuing cancellation.
      Thread.sleep(300); control.cancel();
      assertThrows(java.util.concurrent.ExecutionException.class, () -> work.get(3, TimeUnit.SECONDS));
    }
    assertThrows(java.io.IOException.class, () -> runner.run("@{text=('x'*140000)}",Map.of(),new RunControl(60)));
  }

  @Test void powershellArgumentsRemainDataAndIisMissingSiteIsExplicit() throws Exception {
    var runner = new PowerShellRunner(json);
    String value = "中文'; throw 'must remain data'; #";
    var echo = runner.run("@{value=$a.value}", Map.of("value", value), new RunControl(60));
    assertEquals(value, echo.path("value").asString());
    var iis = runner.run(HostScripts.IIS, Map.of("site", "ssbot-definitely-no-such-site", "pool", ""), new RunControl(60));
    assertTrue(iis.has("error"), iis.toString());
  }
}
