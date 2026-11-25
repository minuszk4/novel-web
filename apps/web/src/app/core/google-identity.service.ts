import { Injectable } from '@angular/core';
import { FirebaseApp, getApp, getApps, initializeApp } from 'firebase/app';
import {
  Auth,
  GoogleAuthProvider,
  getAuth,
  inMemoryPersistence,
  setPersistence,
  signInWithPopup,
  signOut,
} from 'firebase/auth';

export interface FirebaseWebConfiguration {
  apiKey: string;
  authDomain: string;
  projectId: string;
  appId: string;
}

@Injectable({ providedIn: 'root' })
export class GoogleIdentityService {
  private auth?: Auth;

  async initialize(configuration: FirebaseWebConfiguration): Promise<void> {
    const app = this.getOrCreateApp(configuration);
    const auth = getAuth(app);
    await setPersistence(auth, inMemoryPersistence);
    this.auth = auth;
  }

  signInWithGoogle(): Promise<string> {
    if (!this.auth) {
      throw new Error('Firebase Authentication has not been initialized.');
    }

    const provider = new GoogleAuthProvider();
    provider.setCustomParameters({ prompt: 'select_account' });
    // Start the popup synchronously from the button's click handler so browsers keep
    // the user's activation and do not block the authentication window.
    return signInWithPopup(this.auth, provider).then(({ user }) => user.getIdToken());
  }

  async clearTemporarySession(): Promise<void> {
    if (this.auth?.currentUser) {
      await signOut(this.auth);
    }
  }

  private getOrCreateApp(configuration: FirebaseWebConfiguration): FirebaseApp {
    const appName = 'novel-web-auth';
    const existing = getApps().find((app) => app.name === appName);
    return existing ?? initializeApp(configuration, appName);
  }
}
