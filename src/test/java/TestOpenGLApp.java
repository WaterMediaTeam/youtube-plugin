import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.opengl.ARBDebugOutput;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.watermedia.WaterMedia;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.engines.ALEngine;
import org.watermedia.api.media.engines.GLEngine;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.youtube.WaterMediaYT;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Date;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

public class TestOpenGLApp implements Executor {
    private final Queue<Runnable> executor = new LinkedList<>();
    private MediaPlayer player;
    private MRL mrl;

    // The window handle
    private long window;

    // OpenAL device and context
    private long alDevice;
    private long alContext;

    private static final String NAME = "WATERMeDIA: YouTube Plugin Test";

    static {
        final String filename = "logs/watermedia-app.log";
        final File logfile = new File(filename);
        if (logfile.exists() && !logfile.renameTo(new File("logs/watermedia-app-" + new Date() + ".log"))) {
            System.err.println("Failed to rotate log file");
        }

        final ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setStatusLevel(Level.DEBUG);

        final AppenderComponentBuilder console = builder.newAppender("Console", "Console")
                .addAttribute("target", ConsoleAppender.Target.SYSTEM_OUT);
        console.add(builder.newLayout("PatternLayout")
                .addAttribute("pattern", "%highlight{[%d{HH:mm:ss}] [%t/%level] [%logger/%marker]: %msg%n}"));

        final AppenderComponentBuilder file = builder.newAppender("File", "File")
                .addAttribute("fileName", filename)
                .addAttribute("append", true);
        file.add(builder.newLayout("PatternLayout")
                .addAttribute("pattern", "[%d{HH:mm:ss}] [%t/%level] [%logger/%marker]: %msg%n"));

        builder.add(console);
        builder.add(file);
        builder.add(builder.newRootLogger(Level.DEBUG)
                .add(builder.newAppenderRef("Console"))
                .add(builder.newAppenderRef("File")));

        Configurator.initialize(builder.build());
    }

    public void run(final URI url) {
        // Initialize WaterMedia v3
        WaterMedia.start("TEST", null, null, true);

        // Request MRL (starts async loading via IPlatform)
        this.mrl = MediaAPI.getMRL(url.toString());

        this.init();
        this.loop();

        // Cleanup
        if (this.player != null) {
            this.player.release();
        }

        ALC10.alcMakeContextCurrent(NULL);
        ALC10.alcDestroyContext(this.alContext);
        ALC10.alcCloseDevice(this.alDevice);

        glfwFreeCallbacks(this.window);
        glfwDestroyWindow(this.window);
        glfwTerminate();
        glfwSetErrorCallback(null).close();
        System.exit(0);
    }

    private void init() {
        GLFWErrorCallback.createPrint(System.err).set();

        // Wayland does not like opengl that much, so we hint on using X11 instead
        if (Platform.get().equals(Platform.LINUX))
            glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11);

        if (!glfwInit())
            throw new IllegalStateException("Unable to initialize GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);

        this.window = glfwCreateWindow(1280, 720, NAME, NULL, NULL);
        if (this.window == NULL)
            throw new RuntimeException("Failed to create the GLFW window");

        glfwSetKeyCallback(this.window, (window, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
                glfwSetWindowShouldClose(window, true);
            if (key == GLFW_KEY_P && action == GLFW_RELEASE && this.player != null) {
                this.player.togglePlay();
            }
        });

        try (final MemoryStack stack = stackPush()) {
            final IntBuffer pWidth = stack.mallocInt(1);
            final IntBuffer pHeight = stack.mallocInt(1);

            glfwGetWindowSize(this.window, pWidth, pHeight);
            final GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());

            glfwSetWindowPos(
                    this.window,
                    (vidmode.width() - pWidth.get(0)) / 2,
                    (vidmode.height() - pHeight.get(0)) / 2
            );
        }

        glfwMakeContextCurrent(this.window);
        glfwSwapInterval(1);
        glfwShowWindow(this.window);

        // Initialize OpenAL
        this.alDevice = ALC10.alcOpenDevice((ByteBuffer) null);
        if (this.alDevice == NULL)
            throw new RuntimeException("Failed to open default OpenAL device");

        this.alContext = ALC10.alcCreateContext(this.alDevice, (IntBuffer) null);
        ALC10.alcMakeContextCurrent(this.alContext);

        final ALCCapabilities alcCaps = ALC.createCapabilities(this.alDevice);
        AL.createCapabilities(alcCaps);

        try {
            final Field f = MediaAPI.class.getDeclaredField("PLATFORMS");
            f.setAccessible(true);
            ((java.util.LinkedList<?>) f.get(null)).forEach(platform -> System.out.println("Registered platform: " + platform.getClass().getName()));
        } catch (final Exception e) {

        }
    }

    private void loop() {
        GL.createCapabilities();
        ARBDebugOutput.glDebugMessageCallbackARB((source, type, id, severity, length, message, userParam) -> {
            System.out.println(MemoryUtil.memASCII(message));
        }, 0);

        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glEnable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glfwSetWindowSizeCallback(this.window, (window, width, height) -> {
            glViewport(0, 0, width, height);
        });

        while (!glfwWindowShouldClose(this.window)) {
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            // Wait for MRL sources to be ready, then create player
            if (this.player == null && this.mrl.ready()) {
                this.player = MediaAPI.createPlayer(this.mrl, () -> new GLEngine.Builder(Thread.currentThread(), this).build(), () -> new ALEngine(4));

                if (this.player != null) {
                    this.player.start();
                    System.out.println("Player created and started");
                } else {
                    System.err.println("Failed to create player from MRL: " + this.mrl);
                }
            }

            if (this.mrl.hasError()) {
                System.err.println("MRL failed to load sources: " + this.mrl);
                glfwSetWindowShouldClose(this.window, true);
            }

            // Render video texture
            if (this.player != null && this.player.texture() >= 0) {
                glBindTexture(GL_TEXTURE_2D, (int) this.player.texture());

                glColor4f(1, 1, 1, 1);
                glBegin(GL_QUADS); {
                    glTexCoord2f(0, 1); glVertex2f(-1, -1);
                    glTexCoord2f(0, 0); glVertex2f(-1, 1);
                    glTexCoord2f(1, 0); glVertex2f(1, 1);
                    glTexCoord2f(1, 1); glVertex2f(1, -1);
                }
                glEnd();

                if (this.player.ended()) {
                    glfwSetWindowShouldClose(this.window, true);
                }
            }

            glfwSwapBuffers(this.window);
            glfwPollEvents();
            if (!this.executor.isEmpty()) this.executor.remove().run();
        }
    }

    public static void main(final String[] args) {
        final String url = args.length == 0 ? "https://www.youtube.com/watch?v=GjEKrr5EjzA" : args[0];
        new TestOpenGLApp().run(URI.create(url));
    }

    @Override
    public void execute(final Runnable command) {
        this.executor.add(command);
    }
}
