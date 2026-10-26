// Deploy hondoroadbook.com. Cloudflare's build machines do not all have Docker, and wrangler refuses
// to deploy at all when it cannot rebuild the router container's image, which left the Worker and the
// site undeployed on about every other push. So: rebuild the container when Docker is here, and
// otherwise deploy the Worker and the site on their own, leaving the container on its current image.
// Container source (server/) then ships with the next build that lands on a machine with Docker,
// or from any machine with Docker: `npm run deploy`.
import { spawnSync } from "node:child_process";

const docker = spawnSync("docker", ["info"], { stdio: "ignore" }).status === 0;
const args = ["wrangler", "deploy", ...(docker ? [] : ["--containers-rollout=none"]), ...process.argv.slice(2)];
if (!docker) console.log("deploy: no Docker on this machine; deploying the Worker and site only, the router container keeps its current image");
const r = spawnSync("npx", args, { stdio: "inherit", shell: process.platform === "win32" });
process.exit(r.status ?? 1);
