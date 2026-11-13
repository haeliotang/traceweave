// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

// Minimal macOS key agent for traceweave-node. Keys are created by Security.framework with
// kSecAttrIsExtractable=false, so the private key never leaves the Keychain; this process only
// signs by application tag and prints the SPKI public key. It is NOT a Secure Enclave key:
// creating one from an unsigned CLI fails with errSecMissingEntitlement.

#include <CoreFoundation/CoreFoundation.h>
#include <Security/Security.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const unsigned char SPKI_PREFIX[] = {
    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06,
    0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
};

static CFDataRef tag_data(const char *tag) {
    return CFDataCreate(NULL, (const UInt8 *)tag, (CFIndex)strlen(tag));
}

static SecKeyRef find_key(const char *tag) {
    CFMutableDictionaryRef query = CFDictionaryCreateMutable(NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFDataRef tagValue = tag_data(tag);
    CFDictionarySetValue(query, kSecClass, kSecClassKey);
    CFDictionarySetValue(query, kSecAttrApplicationTag, tagValue);
    CFDictionarySetValue(query, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom);
    CFDictionarySetValue(query, kSecReturnRef, kCFBooleanTrue);
    CFTypeRef result = NULL;
    OSStatus status = SecItemCopyMatching(query, &result);
    CFRelease(tagValue);
    CFRelease(query);
    if (status != errSecSuccess) return NULL;
    return (SecKeyRef)result;
}

static void print_spki(SecKeyRef key) {
    CFErrorRef error = NULL;
    SecKeyRef publicKey = SecKeyCopyPublicKey(key);
    CFDataRef point = SecKeyCopyExternalRepresentation(publicKey, &error);
    if (!point || CFDataGetLength(point) != 65) {
        fprintf(stderr, "public key is not a 65-byte P-256 point\n");
        exit(1);
    }
    const UInt8 *bytes = CFDataGetBytePtr(point);
    for (size_t index = 0; index < sizeof(SPKI_PREFIX); index++) printf("%02x", SPKI_PREFIX[index]);
    for (CFIndex index = 0; index < CFDataGetLength(point); index++) printf("%02x", bytes[index]);
    printf("\n");
    CFRelease(point);
    CFRelease(publicKey);
}

static SecKeyRef create_key(const char *tag) {
    SecKeyRef existing = find_key(tag);
    if (existing) return existing;
    CFMutableDictionaryRef attributes = CFDictionaryCreateMutable(NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFMutableDictionaryRef private = CFDictionaryCreateMutable(NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFDataRef tagValue = tag_data(tag);
    CFDictionarySetValue(private, kSecAttrIsPermanent, kCFBooleanTrue);
    CFDictionarySetValue(private, kSecAttrIsExtractable, kCFBooleanFalse);
    CFDictionarySetValue(private, kSecAttrApplicationTag, tagValue);
    CFDictionarySetValue(attributes, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom);
    int bits = 256;
    CFNumberRef size = CFNumberCreate(NULL, kCFNumberIntType, &bits);
    CFDictionarySetValue(attributes, kSecAttrKeySizeInBits, size);
    CFDictionarySetValue(attributes, kSecPrivateKeyAttrs, private);
    CFErrorRef error = NULL;
    SecKeyRef key = SecKeyCreateRandomKey(attributes, &error);
    if (!key) {
        CFShow(error);
        fprintf(stderr, "could not create key %s\n", tag);
        exit(1);
    }
    CFRelease(tagValue);
    CFRelease(size);
    CFRelease(private);
    CFRelease(attributes);
    return key;
}

static int hex_value(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: traceweave-key-agent create|public|sign|delete <tag> [hex-message]\n");
        return 2;
    }
    const char *command = argv[1];
    const char *tag = argv[2];
    if (strcmp(command, "create") == 0) {
        SecKeyRef key = create_key(tag);
        print_spki(key);
        return 0;
    }
    if (strcmp(command, "delete") == 0) {
        CFMutableDictionaryRef query = CFDictionaryCreateMutable(NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
        CFDataRef tagValue = tag_data(tag);
        CFDictionarySetValue(query, kSecClass, kSecClassKey);
        CFDictionarySetValue(query, kSecAttrApplicationTag, tagValue);
        SecItemDelete(query);
        CFRelease(tagValue);
        CFRelease(query);
        printf("deleted\n");
        return 0;
    }
    SecKeyRef key = find_key(tag);
    if (!key) {
        fprintf(stderr, "no key for tag %s\n", tag);
        return 1;
    }
    if (strcmp(command, "public") == 0) {
        print_spki(key);
        return 0;
    }
    if (strcmp(command, "sign") != 0 || argc < 4) {
        fprintf(stderr, "sign requires a hex message\n");
        return 2;
    }
    const char *hex = argv[3];
    size_t length = strlen(hex) / 2;
    unsigned char *message = malloc(length ? length : 1);
    for (size_t index = 0; index < length; index++) {
        int high = hex_value(hex[index * 2]);
        int low = hex_value(hex[index * 2 + 1]);
        if (high < 0 || low < 0) {
            fprintf(stderr, "invalid hex message\n");
            return 2;
        }
        message[index] = (unsigned char)((high << 4) | low);
    }
    CFDataRef data = CFDataCreate(NULL, message, (CFIndex)length);
    CFErrorRef error = NULL;
    CFDataRef signature = SecKeyCreateSignature(key, kSecKeyAlgorithmECDSASignatureMessageX962SHA256, data, &error);
    if (!signature) {
        CFShow(error);
        fprintf(stderr, "signing failed\n");
        return 1;
    }
    const UInt8 *bytes = CFDataGetBytePtr(signature);
    for (CFIndex index = 0; index < CFDataGetLength(signature); index++) printf("%02x", bytes[index]);
    printf("\n");
    free(message);
    return 0;
}
