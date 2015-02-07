package facedukuer;

import org.bytedeco.javacpp.opencv_core;
import org.bytedeco.javacpp.opencv_imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.http.converter.BufferedImageHttpMessageConverter;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsMessagingTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.ByteArrayMessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.config.annotation.AbstractWebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import javax.servlet.http.Part;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import java.util.List;
import java.util.function.BiConsumer;

import static org.bytedeco.javacpp.opencv_core.*;
import static org.bytedeco.javacpp.opencv_objdetect.*;

@SpringBootApplication
@RestController
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @Autowired
    FaceDetector faceDetector;
    @Autowired
    JmsMessagingTemplate jmsTemplate;
    @Autowired
    SimpMessagingTemplate simpTemplate;
    @Value("${faceduker.width:200}")
    int resizedWidth;

    static final Logger log = LoggerFactory.getLogger(App.class);

    @Bean
    BufferedImageHttpMessageConverter bufferedImageHttpMessageConverter() {
        return new BufferedImageHttpMessageConverter();
    }

    @Configuration
    @EnableWebSocketMessageBroker
    static class StompConfig extends AbstractWebSocketMessageBrokerConfigurer {
        @Override
        public void configureMessageBroker(MessageBrokerRegistry config) {
            config.enableSimpleBroker("/queue");
            config.setApplicationDestinationPrefixes("/app");
        }

        @Override
        public void registerStompEndpoints(StompEndpointRegistry registry) {
            registry.addEndpoint("faceduker");
        }

        @Override
        public boolean configureMessageConverters(List<MessageConverter> messageConverters) {
            messageConverters.add(new StringMessageConverter());
            messageConverters.add(new ByteArrayMessageConverter());
            return true;
        }

        @Override
        public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
            registration.setMessageSizeLimit(10 * 1024 * 1024);
        }
    }

    // curl -v -F 'file=@hoge.jpg' http://localhost:8080/duker > after.jpg
    @RequestMapping(value = "/duker", method = RequestMethod.POST)
    BufferedImage duker(@RequestParam Part file) throws IOException {
        Mat source = Mat.createFrom(ImageIO.read(file.getInputStream()));
        faceDetector.detectFaces(source, FaceTranslator::duker);
        BufferedImage image = new BufferedImage(source.cols(), source.rows(), source
                .getBufferedImageType());
        source.copyTo(image);
        return image;
    }

    @RequestMapping(value = "/queue", method = RequestMethod.POST)
    String queue(@RequestParam Part file) throws IOException {
        byte[] src = StreamUtils.copyToByteArray(file.getInputStream());
        jmsTemplate.convertAndSend("processImage", src);
        return "OK";
    }

    @MessageMapping(value = "/duker")
    String duker(String base64Image) {
        byte[] src = Base64.getDecoder().decode(base64Image);
        jmsTemplate.convertAndSend("processImage", src);
        return "OK";
    }

    @JmsListener(destination = "processImage", concurrency = "1-4")
    void handleImageMessage(Message<byte[]> message) throws IOException {
        log.info("received! {}", message);
        try (InputStream stream = new ByteArrayInputStream(message.getPayload())) {
            Mat source = Mat.createFrom(ImageIO.read(stream));
            faceDetector.detectFaces(source, FaceTranslator::duker);

            // resize
            double ratio = ((double) resizedWidth) / source.cols();
            int height = (int) (ratio * source.rows());
            Mat out = new Mat(height, resizedWidth, source.type());
            opencv_imgproc.resize(source, out, new Size(), ratio, ratio, opencv_imgproc.INTER_LINEAR);

            BufferedImage image = out.getBufferedImage();

            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                ImageIO.write(image, "png", baos);
                baos.flush();
                simpTemplate.convertAndSend("/queue/finish", Base64.getEncoder().encodeToString(baos.toByteArray()));
            }
        }
    }
}

@Component
@Scope(value = "prototype", proxyMode = ScopedProxyMode.TARGET_CLASS)
class FaceDetector {
    @Value("${classifierFile:classpath:/haarcascade_frontalface_default.xml}")
    File classifierFile;

    CascadeClassifier classifier;

    static final Logger log = LoggerFactory.getLogger(FaceDetector.class);

    public void detectFaces(Mat source, BiConsumer<Mat, Rect> detectAction) {
        Rect faceDetections = new Rect();
        classifier.detectMultiScale(source, faceDetections);
        int numOfFaces = faceDetections.limit();
        log.info("{} faces are detected!", numOfFaces);
        for (int i = 0; i < numOfFaces; i++) {
            Rect r = faceDetections.position(i);
            detectAction.accept(source, r);
        }
    }

    @PostConstruct
    void init() throws IOException {
        if (log.isInfoEnabled()) {
            log.info("load {}", classifierFile.toPath());
        }
        this.classifier = new CascadeClassifier(classifierFile.toPath()
                .toString());
    }
}

class FaceTranslator {
    public static void duker(Mat source, Rect r) {
        int x = r.x(), y = r.y(), h = r.height(), w = r.width();
        // make the face Duke
        // black upper rectangle
        opencv_core.rectangle(source, new Point(x, y), new Point(x + w, y + h
                / 2), new Scalar(0, 0, 0, 0), -1, CV_AA, 0);
        // white lower rectangle
        opencv_core.rectangle(source, new Point(x, y + h / 2),
                new Point(x + w, y + h), new Scalar(255, 255, 255, 0), -1,
                CV_AA, 0);
        // red center circle
        opencv_core.circle(source, new Point(x + h / 2, y + h / 2),
                (w + h) / 12, new Scalar(0, 0, 255, 0), -1, CV_AA, 0);
    }
}
