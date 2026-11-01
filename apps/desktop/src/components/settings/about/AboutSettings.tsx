import React, { useState, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { getVersion } from "@tauri-apps/api/app";
import { openUrl } from "@tauri-apps/plugin-opener";
import { SettingsGroup } from "../../ui/SettingsGroup";
import { SettingContainer } from "../../ui/SettingContainer";
import { Button } from "../../ui/Button";
import { AppDataDirectory } from "../AppDataDirectory";
import { AppLanguageSelector } from "../AppLanguageSelector";
import { LogDirectory } from "../debug";

const REPO_URL = "https://github.com/Nikhil1920/veena";

const OPEN_SOURCE_LINKS = [
  { key: "sourceCode", url: REPO_URL },
  { key: "reportIssue", url: `${REPO_URL}/issues/new` },
  { key: "license", url: `${REPO_URL}/blob/main/LICENSE` },
  {
    key: "feedback",
    url: "mailto:anr.appcontact@gmail.com?subject=Veena%20feedback",
  },
  { key: "website", url: "https://veena.byanr.com" },
] as const;

export const AboutSettings: React.FC = () => {
  const { t } = useTranslation();
  const [version, setVersion] = useState("");

  useEffect(() => {
    const fetchVersion = async () => {
      try {
        const appVersion = await getVersion();
        setVersion(appVersion);
      } catch (error) {
        console.error("Failed to get app version:", error);
        setVersion("0.1.2");
      }
    };

    fetchVersion();
  }, []);

  return (
    <div className="max-w-3xl w-full mx-auto space-y-6">
      <SettingsGroup title={t("settings.about.title")}>
        <AppLanguageSelector descriptionMode="tooltip" grouped={true} />
        <SettingContainer
          title={t("settings.about.version.title")}
          description={t("settings.about.version.description")}
          grouped={true}
        >
          {/* eslint-disable-next-line i18next/no-literal-string */}
          <span className="text-sm font-mono">v{version}</span>
        </SettingContainer>

        <AppDataDirectory descriptionMode="tooltip" grouped={true} />
        <LogDirectory grouped={true} />
      </SettingsGroup>

      <SettingsGroup
        title={t("settings.about.openSource.title")}
        description={t("settings.about.openSource.description")}
      >
        {OPEN_SOURCE_LINKS.map(({ key, url }) => (
          <SettingContainer
            key={key}
            title={t(`settings.about.openSource.${key}.title`)}
            description={t(`settings.about.openSource.${key}.description`)}
            descriptionMode="inline"
            grouped={true}
          >
            <Button
              variant="secondary"
              size="sm"
              onClick={() =>
                openUrl(url).catch((error) =>
                  console.error("Failed to open link:", error),
                )
              }
            >
              {t(`settings.about.openSource.${key}.button`)}
            </Button>
          </SettingContainer>
        ))}
      </SettingsGroup>

      <SettingsGroup title={t("settings.about.acknowledgments.title")}>
        <SettingContainer
          title="IndicConformer By AI4Bharat"
          description="IndicConformers is a suite of ASR models built to deliver accurate speech-to-text conversion in all 22 official Indian languages."
          grouped={true}
          layout="stacked"
        >
          <div className="text-sm text-mid-gray">
            Veena uses Quantized versions of IndicConformer models for offline
            speech recognition in Indian languages.
          </div>
        </SettingContainer>
        <SettingContainer
          title="Handy"
          description="Handy is an open source speech-to-text application that you can run on your own computer"
          grouped={true}
          layout="stacked"
        >
          <div className="text-sm text-mid-gray">
            Veena is a fork of Handy, Thanks to CJ Pais for creating the
            original app and open sourcing it! and to all the contributors who
            have contributed to the project.
          </div>
        </SettingContainer>
      </SettingsGroup>
    </div>
  );
};
