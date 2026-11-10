export interface ImportFileMetadata {
  name: string;
  size: number;
  type: string;
  extension: string;
}

export interface PrivateWork {
  id: string;
  title: string;
  author: string;
  sourceName: string;
  importedAt: string;
  visibility: 'private';
}

export interface RankRule {
  key: string;
  name: string;
  threshold: number;
  group: string;
}

export interface ApiSystemStatus {
  apiVersion: string;
  environment: string;
  integrations: {
    googleOidcEnabled: boolean;
    momoTestEnabled: boolean;
    momoEnvironment: string;
  };
  features: {
    importProcessingEnabled: boolean;
    cultivationEnabled: boolean;
    commerceSandboxEnabled: boolean;
  };
}
