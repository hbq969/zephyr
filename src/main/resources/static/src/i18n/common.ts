import axios from '@/network/index'
import {msg} from '@/utils/Utils'

export function switchLang(event: any) {
    let langKey = event
    axios({
        url: '/i18n/lang',
        method: 'put',
        data: {lang: langKey}
    }).then((res: any) => {
        if (res.data.state == 'OK') {
            console.log('切换语言: ', langKey)
            window.sessionStorage.setItem('h-sm-lang', langKey)
            window.location.reload()
        } else {
            let content = res.config.baseURL + res.config.url + ': ' + res.data.errorMessage;
            msg(content, "warning")
        }
    }).catch((err: Error) => {
        console.log('', err)
    })
}