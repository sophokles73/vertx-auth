/*
 * Copyright (c) 2021 Dmitry Novikov
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.ext.auth.otp.hotp.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.Credentials;
import io.vertx.ext.auth.otp.Authenticator;
import io.vertx.ext.auth.otp.OtpKey;
import io.vertx.ext.auth.otp.hotp.HotpAuth;
import io.vertx.ext.auth.otp.hotp.HotpAuthOptions;
import io.vertx.ext.auth.otp.OtpCredentials;
import io.vertx.ext.auth.otp.impl.org.openauthentication.otp.OneTimePasswordAlgorithm;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.function.Function;

public class HotpAuthImpl implements HotpAuth {

  private final HotpAuthOptions hotpAuthOptions;

  private Function<String, Future<Authenticator>> fetcher;
  private Function<Authenticator, Future<Void>> updater;

  public HotpAuthImpl(HotpAuthOptions hotpAuthOptions) {
    if (hotpAuthOptions == null) {
      throw new IllegalArgumentException("hotpAuthOptions cannot null");
    }
    this.hotpAuthOptions = hotpAuthOptions;
  }

  @Override
  public void authenticate(JsonObject credentials, Handler<AsyncResult<User>> resultHandler) {
    authenticate(new OtpCredentials(credentials), resultHandler);
  }

  @Override
  public void authenticate(Credentials credentials, Handler<AsyncResult<User>> resultHandler) {
    try {
      OtpCredentials authInfo = (OtpCredentials) credentials;
      authInfo.checkValid(hotpAuthOptions);

      fetcher.apply(authInfo.getIdentifier())
        .onFailure(err -> resultHandler.handle(Future.failedFuture(err)))
        .onSuccess(authenticator -> {
          if (authenticator == null) {
            resultHandler.handle(Future.failedFuture("user is not found"));
          } else {
            long counter = authenticator.getCounter();
            String key = authenticator.getKey();
            String algorithm = authenticator.getAlgorithm();

            OtpKey otpKey = new OtpKey()
              .setKey(key)
              .setAlgorithm(algorithm);

            counter = ++counter;
            Integer authAttempts = authenticator.getAuthAttempts();
            authAttempts = authAttempts != null ? ++authAttempts : 1;
            authenticator.setAuthAttempts(authAttempts);

            String oneTimePassword;

            try {
              oneTimePassword = OneTimePasswordAlgorithm.generateOTP(otpKey.getKeyBytes(), counter, hotpAuthOptions.getPasswordLength(), false, -1);
            } catch (GeneralSecurityException e) {
              resultHandler.handle(Future.failedFuture(e));
              return;
            }

            if (oneTimePassword.equals(authInfo.getCode())) {
              authenticator.setCounter(counter);
              updater.apply(authenticator)
                .onFailure(err -> resultHandler.handle(Future.failedFuture(err)))
                .onSuccess(v -> resultHandler.handle(Future.succeededFuture(createUser(authenticator))));
              return;
            }

            if (hotpAuthOptions.isUsingAttemptsLimit() && authAttempts >= hotpAuthOptions.getAuthAttemptsLimit()) {
              updater.apply(authenticator)
                .onFailure(err -> resultHandler.handle(Future.failedFuture(err)))
                .onSuccess(v -> resultHandler.handle(Future.failedFuture("invalid code")));
              return;
            } else if (hotpAuthOptions.isUsingResynchronization()) {
              for (int i = 0; i < hotpAuthOptions.getLookAheadWindow(); i++) {
                counter = ++counter;

                try {
                  oneTimePassword = OneTimePasswordAlgorithm.generateOTP(otpKey.getKeyBytes(), counter, hotpAuthOptions.getPasswordLength(), false, -1);
                } catch (GeneralSecurityException e) {
                  resultHandler.handle(Future.failedFuture(e));
                  return;
                }

                if (MessageDigest.isEqual(oneTimePassword.getBytes(StandardCharsets.UTF_8), authInfo.getCode().getBytes(StandardCharsets.UTF_8))) {
                  authenticator.setCounter(counter);
                  updater.apply(authenticator)
                    .onFailure(err -> resultHandler.handle(Future.failedFuture(err)))
                    .onSuccess(v -> resultHandler.handle(Future.succeededFuture(createUser(authenticator))));
                  return;
                }
              }
            }

            resultHandler.handle(Future.failedFuture("invalid code"));
          }
        });
    } catch (RuntimeException e) {
      resultHandler.handle(Future.failedFuture(e));
    }
  }

  @Override
  public HotpAuth authenticatorFetcher(Function<String, Future<Authenticator>> fetcher) {
    this.fetcher = fetcher;
    return this;
  }

  @Override
  public HotpAuth authenticatorUpdater(Function<Authenticator, Future<Void>> updater) {
    this.updater = updater;
    return this;
  }

  @Override
  public Future<Authenticator> createAuthenticator(String id, OtpKey otpKey) {
    // Create user in the database
    final Authenticator authenticator = new Authenticator(true)
      .setIdentifier(id)
      .setKey(otpKey.getKey())
      .setAlgorithm(otpKey.getAlgorithm())
      .setCounter(hotpAuthOptions.getCounter());

    return updater
      .apply(authenticator)
      .map(authenticator);
  }

  @Override
  public String generateUri(OtpKey otpKey, String issuer, String user, String label) {
    try {
      if (label == null) {
        if (issuer == null) {
          throw new IllegalArgumentException("label and issuer cannot all be null");
        }
        if (user == null) {
          label = URLEncoder.encode(issuer, "UTF8");
        } else {
          label = URLEncoder.encode(issuer, "UTF8") + ":" + URLEncoder.encode(user, "UTF8");
        }
      }

      // build the parameter
      StringBuilder sb = new StringBuilder();
      // secret is required
      sb.append("secret=").append(otpKey.getKey());
      // issuer is strongly recommended
      if (issuer != null) {
        sb.append("&issuer=").append(URLEncoder.encode(issuer, "UTF8"));
      }
      // algorithm is optional, default is SHA1
      if (otpKey.getAlgorithm() != null) {
        // strip the HMac" part
        if (!otpKey.getAlgorithm().equals("SHA1")) {
          sb.append("&algorithm=").append(otpKey.getAlgorithm());
        }
      }
      // digits is optional, default is 6
      if (hotpAuthOptions.getPasswordLength() != 6) {
        sb.append("&digits=").append(hotpAuthOptions.getPasswordLength());
      }
      // counter is required
      sb.append("&counter=").append(hotpAuthOptions.getCounter());

      return String.format(
        "otpauth://hotp/%s?%s",
        label,
        sb);

    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  private User createUser(Authenticator authenticator) {
    return User.create(
      new JsonObject()
        .put("otp", "hotp")
        .put("counter", authenticator.getCounter())
        .put("auth_attempts", authenticator.getAuthAttempts()));
  }
}
