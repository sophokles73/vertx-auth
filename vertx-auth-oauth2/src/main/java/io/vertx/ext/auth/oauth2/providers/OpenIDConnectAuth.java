/*
 * Copyright 2015 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */
package io.vertx.ext.auth.oauth2.providers;

import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.*;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.impl.http.SimpleHttpClient;
import io.vertx.ext.auth.impl.http.SimpleHttpResponse;
import io.vertx.ext.auth.oauth2.OAuth2Auth;
import io.vertx.ext.auth.oauth2.OAuth2Options;

/**
 * Simplified factory to create an {@link io.vertx.ext.auth.oauth2.OAuth2Auth} for OpenID Connect.
 *
 * @author <a href="mailto:plopes@redhat.com">Paulo Lopes</a>
 */
@VertxGen
public interface OpenIDConnectAuth {

  /**
   * Create a OAuth2Auth provider for OpenID Connect Discovery. The discovery will use the given site in the
   * configuration options and attempt to load the well known descriptor.
   * <p>
   * If the discovered config includes a json web key url, it will be also fetched and the JWKs will be loaded
   * into the OAuth provider so tokens can be decoded.
   *
   * @param vertx   the vertx instance
   * @param config  the initial config, it should contain a site url
   * @param handler the instantiated Oauth2 provider instance handler
   */
  static void discover(final Vertx vertx, final OAuth2Options config, final Handler<AsyncResult<OAuth2Auth>> handler) {
    if (config.getSite() == null) {
      handler.handle(Future.failedFuture("issuer cannot be null"));
      return;
    }

    // compute paths with variables, at this moment it is only relevant that
    // the paths and site are properly computed
    config.replaceVariables(false);

    final String oidc_discovery_path = "/.well-known/openid-configuration";

    // The site and issuer are used interchangeably here and can be confusing in some cases. A small replacement can
    // happen at this time to ensure that the config is correct.
    String issuer = config.getSite();
    if (issuer.endsWith(oidc_discovery_path)) {
      issuer = issuer.substring(0, issuer.length() - oidc_discovery_path.length());
    }

    final SimpleHttpClient httpClient = new SimpleHttpClient(
      vertx,
      config.getUserAgent(),
      config.getHttpClientOptions());

    // the response follows the OpenID Connect provider metadata spec:
    // https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderMetadata
    httpClient.fetch(
      HttpMethod.GET,
      issuer + oidc_discovery_path,
      new JsonObject()
        .put("Accept", "application/json"),
      null,
      fetch -> {
        if (fetch.failed()) {
          handler.handle(Future.failedFuture(fetch.cause()));
          return;
        }

        final SimpleHttpResponse response = fetch.result();

        if (response.statusCode() != 200) {
          handler.handle(Future.failedFuture("Bad Response [" + response.statusCode() + "] " + response.body()));
          return;
        }

        if (!response.is("application/json")) {
          handler.handle(Future.failedFuture("Cannot handle Content-Type: " + response.headers().get("Content-Type")));
          return;
        }

        final JsonObject json = response.jsonObject();

        // some providers return errors as JSON too
        if (json.containsKey("error")) {
          // attempt to handle the error as a string
          handler.handle(Future.failedFuture(json.getString("error_description", json.getString("error"))));
          return;
        }

        // issuer validation
        if (config.isValidateIssuer()) {
          String issuerEndpoint = json.getString("issuer");
          if (issuerEndpoint != null) {
            // the provider is letting the user know the issuer endpoint, so we need to validate
            // as in vertx oauth the issuer (site config) is a url without the trailing slash we
            // will compare the received endpoint without the final slash is present
            if (issuerEndpoint.endsWith("/")) {
              issuerEndpoint = issuerEndpoint.substring(0, issuerEndpoint.length() - 1);
            }

            if (!config.getSite().equals(issuerEndpoint)) {
              handler.handle(Future.failedFuture("issuer validation failed: received [" + issuerEndpoint + "]"));
              return;
            }
          }
        }

        config.setAuthorizationPath(json.getString("authorization_endpoint"));
        config.setTokenPath(json.getString("token_endpoint"));
        config.setLogoutPath(json.getString("end_session_endpoint"));
        config.setRevocationPath(json.getString("revocation_endpoint"));
        config.setUserInfoPath(json.getString("userinfo_endpoint"));
        config.setJwkPath(json.getString("jwks_uri"));
        config.setIntrospectionPath(json.getString("introspection_endpoint"));

        if (json.containsKey("issuer")) {
          // the discovery document includes the issuer, this means we can and should assert that source of all tokens
          // when in JWT form
          JWTOptions jwtOptions = config.getJWTOptions();
          if (jwtOptions == null) {
            jwtOptions = new JWTOptions();
            config.setJWTOptions(jwtOptions);
          }
          // configure the issuer
          jwtOptions.setIssuer(json.getString("issuer"));
        }


        // reset config
        config.setSupportedGrantTypes(null);

        if (json.containsKey("grant_types_supported")) {
          // optional config
          JsonArray flows = json.getJsonArray("grant_types_supported");
          flows.forEach(el -> config.addSupportedGrantType((String) el));

          if (!flows.contains(config.getFlow().getGrantType())) {
            handler.handle(Future.failedFuture("unsupported flow: " + config.getFlow().getGrantType() + ", allowed: " + flows));
            return;
          }
        } else {
          // https://datatracker.ietf.org/doc/html/rfc8414
          // specifies that if omitted, assume the default: ["authorization_code", "implicit"]
          config
            .addSupportedGrantType("authorization_code")
            .addSupportedGrantType("implicit");
        }

        try {
          // the constructor might fail if the configuration is incomplete
          final OAuth2Auth oidc = OAuth2Auth.create(vertx, config);

          if (config.getJwkPath() != null) {
            oidc.jWKSet(v -> {
              if (v.failed()) {
                handler.handle(Future.failedFuture(v.cause()));
                return;
              }

              handler.handle(Future.succeededFuture(oidc));
            });
          } else {
            handler.handle(Future.succeededFuture(oidc));
          }
        } catch (IllegalArgumentException | IllegalStateException e) {
          handler.handle(Future.failedFuture(e));
        }
      });
  }

  /**
   * Create a OAuth2Auth provider for OpenID Connect Discovery. The discovery will use the given site in the
   * configuration options and attempt to load the well known descriptor.
   * <p>
   * If the discovered config includes a json web key url, it will be also fetched and the JWKs will be loaded
   * into the OAuth provider so tokens can be decoded.
   *
   * @param vertx  the vertx instance
   * @param config the initial config, it should contain a site url
   * @return future with the instantiated Oauth2 provider instance handler
   * @see OpenIDConnectAuth#discover(Vertx, OAuth2Options, Handler)
   */
  static Future<OAuth2Auth> discover(final Vertx vertx, final OAuth2Options config) {
    Promise<OAuth2Auth> promise = Promise.promise();
    discover(vertx, config, promise);
    return promise.future();
  }
}
