package facedukuer;

import org.bytedeco.javacpp.opencv_core;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.BufferedImageHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import javax.servlet.http.Part;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
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

    @Bean
    BufferedImageHttpMessageConverter bufferedImageHttpMessageConverter() {
        return new BufferedImageHttpMessageConverter();
    }

    @RequestMapping(value = "/")
    String hello() {
        return "Hello World!";
    }

    // curl -v -F 'file=@hoge.jpg' http://localhost:8080/process > after.jpg
    @RequestMapping(value = "/ducker", method = RequestMethod.POST)
    BufferedImage duker(@RequestParam Part file) throws IOException {
        Mat source = Mat.createFrom(ImageIO.read(file.getInputStream()));
        faceDetector.detectFaces(source, FaceTranslator::duker);
        BufferedImage image = new BufferedImage(source.cols(), source.rows(), source
                .getBufferedImageType());
        source.copyTo(image);
        return image;
    }
}

@Component
class FaceDetector {
    @Value("${classifierFile:classpath:/haarcascade_frontalface_default.xml}")
    File classifierFile;

    CascadeClassifier classifier;

    Logger log = LoggerFactory.getLogger(FaceDetector.class);

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
