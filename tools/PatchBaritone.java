import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Bakes the Baritone 1.21.x deadlock fix into baritone's own jar, so people running
 * <b>stock</b> Baritone (without our mod) no longer freeze on {@code #mine}.
 *
 * <p>Target: {@code baritone.api.utils.BlockOptionalMeta$a#method_30349} — a fake
 * ServerWorld whose {@code getRegistryManager()} joins a CompletableFuture that only
 * the render thread could complete. When {@code #mine} runs on the render thread that
 * join never returns.</p>
 *
 * <p>Replacement body (no branches, so no StackMapTable changes):</p>
 * <pre>
 *   return MinecraftClient.getInstance().world.getRegistryManager();
 * </pre>
 */
public final class PatchBaritone {
    private static final String TARGET_CLASS = "baritone/api/utils/BlockOptionalMeta$a";
    private static final String TARGET_METHOD = "method_30349";

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: PatchBaritone <in.jar> <out.jar>");
            System.exit(2);
        }
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        Map<String, Integer> methods = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(in))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = zip.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                entries.put(entry.getName(), buffer.toByteArray());
            }
        }

        byte[] patched = entries.get(TARGET_CLASS + ".class");
        if (patched == null) {
            throw new IllegalStateException("class not found in jar: " + TARGET_CLASS);
        }

        ClassNode node = new ClassNode();
        new ClassReader(patched).accept(node, 0);

        boolean registryPatched = false;
        boolean serverPatched = false;
        for (MethodNode method : node.methods) {
            // 1) getRegistryManager(): return the live client world's manager instead of
            //    joining a future the render thread can never complete.
            if (method.name.equals("method_30349") && !registryPatched) {
                if (!method.desc.endsWith(")Lnet/minecraft/class_5455;")) {
                    throw new IllegalStateException("unexpected descriptor: " + method.desc);
                }
                InsnList replacement = new InsnList();
                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "net/minecraft/class_310", "method_1551", "()Lnet/minecraft/class_310;", false));
                replacement.add(new FieldInsnNode(Opcodes.GETFIELD,
                        "net/minecraft/class_310", "field_1687", "Lnet/minecraft/class_638;"));
                replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/class_638", "method_30349", "()Lnet/minecraft/class_5455;", false));
                replacement.add(new InsnNode(Opcodes.ARETURN));
                replaceBody(method, replacement);
                registryPatched = true;
                continue;
            }
            // 2) getServer(): this fake ServerWorld has no server field, so baritone's
            //    own code NPEs when it asks for one. Point it at the integrated server.
            if (method.name.equals("method_8503") && !serverPatched) {
                if (!method.desc.endsWith(")Lnet/minecraft/class_1132;")) {
                    throw new IllegalStateException("unexpected descriptor: " + method.desc);
                }
                InsnList replacement = new InsnList();
                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "net/minecraft/class_310", "method_1551", "()Lnet/minecraft/class_310;", false));
                replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/class_310", "method_1576", "()Lnet/minecraft/class_1132;", false));
                replacement.add(new InsnNode(Opcodes.ARETURN));
                replaceBody(method, replacement);
                serverPatched = true;
            }
        }

        if (!registryPatched) {
            throw new IllegalStateException("method not found: method_30349");
        }

        // getServer() lives on the superclass (ServerWorld), so when it is not overridden
        // here we add an override that returns the integrated server instead of null.
        if (!serverPatched) {
            MethodNode override = new MethodNode(Opcodes.ACC_PUBLIC,
                    "method_8503", "()Lnet/minecraft/class_1132;", null, null);
            InsnList replacement = new InsnList();
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "net/minecraft/class_310", "method_1551", "()Lnet/minecraft/class_310;", false));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/class_310", "method_1576", "()Lnet/minecraft/class_1132;", false));
            replacement.add(new InsnNode(Opcodes.ARETURN));
            replaceBody(override, replacement);
            node.methods.add(override);
            serverPatched = true;
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        entries.put(TARGET_CLASS + ".class", writer.toByteArray());

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                if (entry.getKey().startsWith("META-INF/") && entry.getKey().toUpperCase().endsWith(".SF")
                        || entry.getKey().toUpperCase().endsWith(".RSA")) {
                    continue;
                }
                ZipEntry created = new ZipEntry(entry.getKey());
                zip.putNextEntry(created);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }

        System.out.println("patched getRegistryManager=" + registryPatched
                + " getServer=" + serverPatched);
        System.out.println("wrote " + out + " (" + Files.size(out) + " bytes)");
    }

    /** Swap a method body for a straight-line replacement (no labels, so frame data stays valid). */
    private static void replaceBody(MethodNode method, InsnList replacement) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        for (AbstractInsnNode insn : replacement) {
            method.instructions.add(insn);
        }
        method.maxStack = Math.max(method.maxStack, 1);
        method.maxLocals = Math.max(method.maxLocals, 1);
    }
}
