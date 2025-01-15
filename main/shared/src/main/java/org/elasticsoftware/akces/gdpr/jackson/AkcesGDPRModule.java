/*
 * Copyright 2022 - 2025 The Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package org.elasticsoftware.akces.gdpr.jackson;

import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.Module;
import org.semver4j.Semver;

import java.net.URL;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static java.lang.String.format;

public class AkcesGDPRModule extends Module {
    private static Version extractVersion() {
        String className = format("/%s.class", AkcesGDPRModule.class.getName().replace('.', '/'));
        URL resource = AkcesGDPRModule.class.getResource(className);
        if (resource != null) {
            String classPath = resource.toString();
            if (!classPath.startsWith("jar")) {
                // Class not from JAR, cannot determine version
                return Version.unknownVersion();
            }

            String manifestPath = classPath.substring(0, classPath.lastIndexOf("!") + 1) +
                    "/META-INF/MANIFEST.MF";
            try {
                Manifest manifest = new Manifest(new URL(manifestPath).openStream());
                Attributes attr = manifest.getMainAttributes();
                String value = attr.getValue("Implementation-Version");
                if (value != null) {
                    return generateVersion(Semver.parse(value));
                } else {
                    return Version.unknownVersion();
                }
            } catch (Exception e) {
                return Version.unknownVersion();
            }
        } else {
            return Version.unknownVersion();
        }
    }

    public static Version generateVersion(Semver semver) {
        if (semver != null) {
            return new Version(
                    semver.getMajor(),
                    semver.getMinor(),
                    semver.getPatch(),
                    !semver.getPreRelease().isEmpty() ? semver.getPreRelease().get(0) : null,
                    "org.elasticsoftwarefoundation.akces",
                    "akces-runtime");
        } else {
            return Version.unknownVersion();
        }
    }

    @Override
    public String getModuleName() {
        return "AkcesGDPR";
    }

    @Override
    public Version version() {
        return extractVersion();
    }

    @Override
    public void setupModule(SetupContext setupContext) {
        setupContext.addBeanSerializerModifier(new GDPRDataSerializerModifier());
        setupContext.addBeanDeserializerModifier(new GDPRDataDeserializerModifier());
    }
}
