import { spawnSync } from "node:child_process";

const executable = process.platform === "win32" ? "mvnw.cmd" : "./mvnw";
const result = spawnSync(executable, process.argv.slice(2), {
  cwd: process.cwd(),
  shell: process.platform === "win32",
  stdio: "inherit",
});

if (result.error) {
  console.error(result.error.message);
  process.exit(1);
}

process.exit(result.status ?? 1);
