(ns kmet.ai.test-http
  "The provider HTTP decorator: the request's hooks are arguments (pi:
   before_provider_headers / after_provider_response), never installed
   globals — so the decorator needs no kmet.ai require and the transport can
   be redef'ed away here."
  (:require
   [clojure.test :as t]
   [kmet.ai.http :as ai-http]
   [kmet.libs.http :as http]))

(t/deftest test-request-applies-header-and-response-hooks
  (let [sent (atom nil)
        responses (atom [])]
    (with-redefs [http/request (fn [opts]
                                 (reset! sent opts)
                                 {:status 200 :headers {"h" "v"} :body "b"})]
      (let [response (ai-http/request "http://x" {:headers {"a" "b"}} nil
                                      {:on-headers (fn [h] (assoc h "c" "d"))
                                       :on-response (fn [r] (swap! responses conj r))})]
        (t/is (= {:status 200 :headers {"h" "v"} :body "b"} response)
              "the transport response is returned unchanged")
        (t/is (= "http://x" (:url @sent)))
        (t/is (= :post (:method @sent)) "the provider streams' default method")
        (t/is (= {"a" "b" "c" "d"} (:headers @sent))
              "the headers hook runs before the transport call")
        (t/is (= [{:status 200 :headers {"h" "v"}}] @responses)
              "the response hook sees status + headers after the call")))))

(t/deftest test-request-without-hooks-is-passthrough
  (let [sent (atom nil)]
    (with-redefs [http/request (fn [opts]
                                 (reset! sent opts)
                                 {:status 204 :headers {} :body ""})]
      (doseq [hooks [nil {} {:on-headers identity}]]
        (t/is (some? (ai-http/request "http://x" {:headers {"a" "b"}} nil hooks)))
        (t/is (= {"a" "b"} (:headers @sent))
              (str "no :on-headers in " (pr-str hooks) " — headers untouched"))))))
