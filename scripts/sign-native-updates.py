"""Sign native update metadata as standard JWS Compact (RS256); never upload the private key."""
import argparse,base64,copy,hashlib,json,os,time
from pathlib import Path
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import padding,rsa

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--key',type=Path,required=True)
parser.add_argument('--source',type=Path,default=root/'updates/share-preview.json')
parser.add_argument('--output',type=Path,default=root/'updates/native-updates.json')
parser.add_argument('--init-key',action='store_true')
parser.add_argument('--days',type=int,default=90)
args=parser.parse_args()
if not args.key.exists():
    if not args.init_key:raise SystemExit('Signing key missing; use the existing release key, do not rotate it implicitly')
    args.key.parent.mkdir(parents=True,exist_ok=True)
    key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
    with os.fdopen(os.open(args.key,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'wb') as file:
        file.write(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
else:key=serialization.load_pem_private_key(args.key.read_bytes(),password=None)
public=key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
trust=root/'updates/native-update-public.pem'
if trust.exists() and trust.read_text()!=public:raise SystemExit('Pinned public key mismatch: refusing an implicit signing-key change')
trust.write_text(public)
b64=lambda data:base64.urlsafe_b64encode(data).rstrip(b'=')
now=int(time.time());kid='floatclip-updates-20261023'
release=json.loads(args.source.read_text(encoding='utf-8'))
assert release['schema']==1 and release['channel']=='share-preview'
tokens={}
for channel in ['share-preview','share-dev']:
    item=copy.deepcopy(release);item['channel']=channel
    payload={'schema':1,'issuedAt':now,'expiresAt':now+args.days*86400,'release':item}
    header={'alg':'RS256','kid':kid,'typ':'JOSE'}
    content=b64(json.dumps(header,separators=(',',':')).encode())+b'.'+b64(json.dumps(payload,ensure_ascii=False,separators=(',',':')).encode())
    tokens[channel]=(content+b'.'+b64(key.sign(content,padding.PKCS1v15(),hashes.SHA256()))).decode()
args.output.write_text(json.dumps({'nativeUpdates':tokens},indent=2)+'\n',encoding='utf-8')
web_metadata=root/'web/public/release.json'
if web_metadata.exists():
    web=json.loads(web_metadata.read_text(encoding='utf-8'));web['nativeUpdates']=tokens
    web_metadata.write_text(json.dumps(web,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(root/'windows/FloatClip.Desktop/UpdateTrust.cs').write_text('namespace FloatClip.Share;\ninternal static class UpdateTrust\n{\n    internal const string KeyId="'+kid+'";\n    internal const string PublicKey="""\n'+public+'""";\n}\n',encoding='utf-8')
der=key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)
(root/'nearby-probe/src/main/java/com/floatclip/probe/UpdateTrust.kt').write_text('package com.floatclip.probe\ninternal object UpdateTrust {\n    const val KEY_ID="'+kid+'"\n    const val PUBLIC_KEY="'+base64.b64encode(der).decode()+'"\n}\n',encoding='utf-8')
print(json.dumps({'signedVersion':release['version'],'expiresAt':now+args.days*86400,'publicKeySha256':hashlib.sha256(der).hexdigest(),'channels':list(tokens)}))
