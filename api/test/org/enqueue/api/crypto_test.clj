(ns org.enqueue.api.crypto-test
  (:require [clojure.test :refer :all]
            [org.enqueue.api.crypto :as crypto]
            [clojure.string :as str]))

(defn rand-unicode-string []
  (String.
    (.getBytes
      (apply str
             (map char (take (rand-int 300)
                             (repeatedly #(rand-int 65536))))))
    "UTF-8"))

(defn b64-count
  "Return expected character count of a Base 64 encoded string of
  'input-length' characters.

  See: <https://stackoverflow.com/questions/13378815/base64-length-calculation>"
  [input-length]
  (loop [len (long (Math/ceil (* 4 (/ input-length 3))))]
    (if (zero? (mod len 4))
      (do
        (assert (not= input-length len))
        (assert (zero? (mod len 4)))
        (assert (> len input-length))
        len)
      (recur (inc len)))))

(deftest base64-encode
  (testing "Can correctly Base64 encode."
    (is (empty? (crypto/base64-encode "")))
    (is (= "Rm9v" (crypto/base64-encode "Foo")))
    (let [rand-string (rand-unicode-string)]
      (when-not (empty? rand-string)
        (is (not= rand-string (crypto/base64-encode rand-string)))))))

(deftest base64-decode
  (testing "Can correctly Base64 decode."
    (is (empty? (crypto/base64-decode "")))
    (is (= "Foo" (crypto/base64-decode "Rm9v")))
    (let [rand-string (rand-unicode-string)
          encoded-str (crypto/base64-encode rand-string "UTF-8")
          decoded-str (crypto/base64-decode encoded-str "UTF-8")]
      (when-not (empty? rand-string)
        (is (not= rand-string encoded-str))
        (is (not= encoded-str decoded-str))
        (is (= rand-string decoded-str))))))

(deftest hash-password--hashed+can-verify
  (testing "Can hash password and verify password against hash."
    (dotimes [_ 10]
      (let [password (rand-unicode-string)
            hashed-password (crypto/hash-password password)]
        ;; Base64 encoded password-hash does not end with ASCII NULL characters.
        (is (not (str/ends-with? hashed-password "\u0000")))
        ;; Password hashing didn't return input password.
        (is (not= password hashed-password))
        (is (not= (crypto/base64-encode password) hashed-password))
        ;; Base64 encoded password is 172 characters long.
        (is (= (b64-count 128) (count hashed-password)))
        ;; Hashed password is 128 characters long.
        (is (= 128 (count (crypto/base64-decode hashed-password "US-ASCII"))))
        ;; Password verification works on correct password.
        (is (crypto/valid-password? hashed-password password))
        ;; Password verification fails on invalid password.
        (is (not (crypto/valid-password? hashed-password
                                         (rand-unicode-string))))))))

(deftest generate-random-bytes
  (testing "Can generate random string of bytes."
    (dotimes [_ 10]
      (let [size (rand-int (inc 2048))
            bs   (crypto/random-bytes size)]
        ;; Base 64 encoded string contains correct number of characters.
        (is (= (b64-count size) (count bs)))
        ;; Actual string is 'size' bytes long.
        (is (= size (count (crypto/base64-decode bs "ISO-8859-1"))))
        ;; Base64 decodes string to same result every time.
        (is (= (crypto/base64-decode bs "ISO-8859-1")
               (crypto/base64-decode bs "ISO-8859-1")))
        ;; Will not generate same on next run.
        (is (not= bs (crypto/random-bytes size)))))))

(deftest generate-valid-signing-key
  (testing "Can generate valid signing key."
    (dotimes [_ 10]
      (let [key (crypto/new-signing-key)]
        ;; Base 64 encoded key is 44 chars long.
        (is (= (b64-count 32) (count key)))
        ;; Actual key is 32 bytes long.
        (is (= 32 (count (crypto/base64-decode key "ISO-8859-1"))))
        ;; Base64 decodes key to same result every time.
        (is (= (crypto/base64-decode key "ISO-8859-1")
               (crypto/base64-decode key "ISO-8859-1")))
        ;; Will not generate same on next run.
        (is (not= key (crypto/new-signing-key)))))))

(deftest generates-verifible-signature
  (testing "Can sign a message and check if the signature is valid or invalid."
    (dotimes [_ 10]
      (let [key (crypto/new-signing-key)
            msg (rand-unicode-string)
            sig (crypto/sign-message key msg)]
        ;; Base 64 encoded signature is 44 chars long.
        (is (= (b64-count 32) (count sig)))
        ;; Actual signature is 32 bytes long.
        (is (= 32 (count (crypto/base64-decode sig "ISO-8859-1"))))
        ;; Signature is not the key.
        (is (not= sig key))
        ;; Signature is not the message.
        (is (not= sig msg))
        ;; Signature verification succeeds.
        (is (crypto/valid-signature? key msg sig))
        ;; Signature verification fails on modified message.
        (is (not (crypto/valid-signature? key (rand-unicode-string) sig)))
        ;; Signature verification fails on different key.
        (is (not (crypto/valid-signature? (crypto/new-signing-key) msg sig)))
        ;; Signature verification fails on invalid key.
        (is (not (crypto/valid-signature? nil msg sig)))
        ;; Signature verification fails on invalid signature.
        (is (not (crypto/valid-signature? key msg nil)))
        ;; Signature verification fails on modified signature.
        (is (not (crypto/valid-signature? key msg (crypto/sign-message key "Foo"))))))))
