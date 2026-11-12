// The page's absolute addresses, as Node finds them: the page imports three.js from the robot's
// server (/three/three.module.min.js), and the build puts those files in three/, beside page/ and
// test/ (build.gradle's pageForNode).

const ROOT = new URL("../", import.meta.url);

export async function resolve(specifier, context, nextResolve) {
  if (specifier.startsWith("/three/")) {
    return { url: new URL(specifier.slice(1), ROOT).href, shortCircuit: true };
  }
  return nextResolve(specifier, context);
}
