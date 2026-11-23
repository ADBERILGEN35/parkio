package com.parkio.media.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * The shipped upload allow-list (the service's application.yml, not the test copy) offers only
 * formats the normalizer can decode, so no allowed upload can fail with INVALID_IMAGE because of
 * its format alone. WebP was allowed although the runtime has no WebP reader (H-02). The web app,
 * {@code @parkio/validation} and the legacy mobile app mirror this list.
 */
class MediaUploadAllowListTest {

    @Test
    void shippedAllowListOffersOnlyFormatsTheRuntimeCanDecode() throws IOException {
        List<String> allowed = shippedAllowList();

        assertThat(allowed).allSatisfy(type -> assertThat(ImageIO.getImageReadersByMIMEType(type).hasNext())
                .as("ImageIO reader for %s", type)
                .isTrue());
        assertThat(allowed).containsExactly("image/jpeg", "image/png");
    }

    private static List<String> shippedAllowList() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("main-application", new FileSystemResource("src/main/resources/application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return Binder.get(environment)
                .bind("parkio.media.allowed-content-types", Bindable.listOf(String.class))
                .orElseThrow(() -> new IllegalStateException("parkio.media.allowed-content-types is not set"));
    }
}
