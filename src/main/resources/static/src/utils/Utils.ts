import {ElNotification, ElMessage} from 'element-plus'
import type {EpPropMergeType} from "element-plus/es/utils/vue/props/types"
import axios from '@/network/index'
import * as CryptoJS from 'crypto-js';
import JSEncrypt from "jsencrypt"

export function jsonPretty(str: string, tab?: number | undefined): string {
  const obj = JSON.parse(str)
  return tab == undefined ?
      JSON.stringify(obj, null, 2) :
      JSON.stringify(obj, null, tab);
}

export function notify(title: string, message: string,
                       type: EpPropMergeType<StringConstructor,
                           "success" | "warning" | "info" | "error", unknown>): void {
  ElNotification({
    title: title || '提示',
    message: message,
    type: type,
    position: "top-right",
    showClose: true,
    // offset: 50,
  })
}

export function msg(message: string,
                    type: EpPropMergeType<StringConstructor,
                        "success" | "warning" | "info" | "error", unknown>): void {
  ElMessage({
    message: message,
    type: type,
  })
}

export function objectEmpty(obj: any): boolean {
  return obj && Object.keys(obj).length == 0
}

export class Page {
  pageNum: number;
  pageSize: number;
  total: number;

  constructor(pageNum: number, pageSize: number, total: number) {
    this.pageNum = pageNum;
    this.pageSize = pageSize;
    this.total = total;
  }

}

export function downFile(options: any) {
  axios(options).then((res: any) => {
    const blob = new Blob([res.data]);
    const fileName = options.folderName + '.' + options.fileExtension;
    if ('download' in document.createElement('a')) { // 非IE下载
      const elink = document.createElement('a');
      elink.download = fileName;
      elink.style.display = 'none';
      elink.href = URL.createObjectURL(blob);
      document.body.appendChild(elink);
      elink.click();
      URL.revokeObjectURL(elink.href);// 释放URL 对象
      document.body.removeChild(elink);
    } else { // IE10+下载
      // if (navigator) {
      //   navigator.msSaveBlob(blob, fileName);
      // }
    }
    msg('导出成功', 'success')
  }).catch((err: any) => {
    console.error(err)
    msg('导出异常', 'error')
  });
}

export function decryptAES(word: any, key: any, iv: any): string {
  let base64 = CryptoJS.enc.Base64.parse(word);
  let src = CryptoJS.enc.Base64.stringify(base64);
  key = CryptoJS.enc.Utf8.parse(key);
  iv = CryptoJS.enc.Utf8.parse(iv);
  let decrypt = CryptoJS.AES.decrypt(src, key, {
    iv: iv,
    mode: CryptoJS.mode.CBC,
    padding: CryptoJS.pad.Pkcs7
  });
  var decryptedStr = decrypt.toString(CryptoJS.enc.Utf8);
  return decryptedStr.toString();
}

//加密方法
export function encryptAES(word: any, key: any, iv: any): string {
  let srcs = CryptoJS.enc.Utf8.parse(word);
  key = CryptoJS.enc.Utf8.parse(key);
  iv = CryptoJS.enc.Utf8.parse(iv);
  let encrypted = CryptoJS.AES.encrypt(srcs, key, {
    iv: iv,
    mode: CryptoJS.mode.CBC,
    padding: CryptoJS.pad.Pkcs7
  });
  //返回base64
  return CryptoJS.enc.Base64.stringify(encrypted.ciphertext);
}

// 生成16字节(128位)的随机AES密钥
export const generateRandomAESKey = (len: number) => {
  // 生成16字节随机数据
  const randomBytes = CryptoJS.lib.WordArray.random(len);

  // 转换为十六进制字符串表示
  const keyHex = randomBytes.toString(CryptoJS.enc.Hex);

  return {
    key: randomBytes,  // CryptoJS内部格式
    keyHex: keyHex     // 十六进制字符串表示
  };
};

// RSA加密函数
export const encryptRSA = (plaintext: string, publicKey: string) => {
  const encrypt = new JSEncrypt();
  encrypt.setPublicKey("-----BEGIN PUBLIC KEY-----"+publicKey+"-----END PUBLIC KEY-----");
  const encrypted = encrypt.encrypt(plaintext);
  return encrypted;
};

export interface RASOptions<T = any> {
  rsaPub?: string,
  body?: any | {},
  params?: any | {},
  responseDecrypt?: boolean,
  url: string,
  method?: 'POST' | 'PUT' | 'DELETE' | 'PATCH' | 'post' | 'put' | 'delete' | 'patch',
  suc: (data: T, isDecrypt: boolean) => void
}

export const post_ras = <T = any>(options: RASOptions<T>) => {
  const {
    rsaPub = sessionStorage.getItem('publicKey') || '',
    body = {},
    params = {},
    responseDecrypt = true,
    url,
    method = 'POST',
    suc
  } = options;
  const key = generateRandomAESKey(16).keyHex
  const iv = generateRandomAESKey(16).keyHex
  const data = {
    key: encryptRSA(key, rsaPub),
    iv: encryptRSA(iv, rsaPub),
    body: encryptAES(JSON.stringify(body), key, iv)
  }
  axios({
    url: url,
    method: method,
    data: data,
    params: params,
  }).then((res: any) => {
    if (responseDecrypt) {
      let bodyDecrypted = decryptAES(res.data, key, iv)
      let b = JSON.parse(bodyDecrypted)
      if (b.state == 'OK') {
        let c = b.body || {} as any
        suc(c, responseDecrypt)
      } else {
        msg(b.errorMessage, 'warning')
      }
    } else {
      if (res.data.state == 'OK') {
        suc(res.data.body, responseDecrypt)
      } else {
        msg(res.data.errorMessage, 'warning')
      }
    }
  }).catch((err: any) => {
    console.log('', err)
    msg(err?.response.data.errorMessage, 'error')
  })
}