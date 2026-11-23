import Footer from '@/components/Footer';
import {
  getLoginUserUsingGET,
  requestEmailLoginCodeUsingPOST,
  userLoginByEmailUsingPOST,
  userLoginUsingPOST,
} from '@/services/yubi/userController';
import { Link } from '@@/exports';
import { LockOutlined, MailOutlined, SafetyOutlined, UserOutlined } from '@ant-design/icons';
import { LoginForm, ProFormText } from '@ant-design/pro-components';
import { useEmotionCss } from '@ant-design/use-emotion-css';
import { Helmet, history, useModel } from '@umijs/max';
import { Button, Form, message, Space, Tabs } from 'antd';
import React, { useEffect, useState } from 'react';
import { flushSync } from 'react-dom';
import Settings from '../../../../config/defaultSettings';

type LoginFormValues = API.UserLoginRequest & API.UserEmailLoginRequest;

const Login: React.FC = () => {
  const [type, setType] = useState<string>('account');
  const [form] = Form.useForm<LoginFormValues>();
  const [sendingCode, setSendingCode] = useState(false);
  const [cooldown, setCooldown] = useState(0);
  const { setInitialState } = useModel('@@initialState');

  const containerClassName = useEmotionCss(() => {
    return {
      display: 'flex',
      flexDirection: 'column',
      height: '100vh',
      overflow: 'auto',
      backgroundImage:
        "url('https://mdn.alipayobjects.com/yuyan_qk0oxh/afts/img/V-_oS6r-i7wAAAAAAAAAAAAAFl94AQBr')",
      backgroundSize: '100% 100%',
    };
  });

  useEffect(() => {
    if (cooldown <= 0) return undefined;
    const timer = window.setInterval(() => {
      setCooldown((prev) => (prev <= 1 ? 0 : prev - 1));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [cooldown]);

  const fetchUserInfo = async () => {
    const res = await getLoginUserUsingGET();
    if (res?.data) {
      flushSync(() => {
        setInitialState((s) => ({
          ...s,
          currentUser: res.data,
        }));
      });
    }
  };

  const handleSendEmailCode = async () => {
    const email = (form.getFieldValue('email') || '').trim();
    if (!email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      message.warning('请输入已绑定的有效邮箱');
      return;
    }
    setSendingCode(true);
    try {
      const res = await requestEmailLoginCodeUsingPOST({ email });
      if (res.code === 0) {
        setCooldown(60);
        message.success(`登录验证码已发送至 ${res.data || email}，请查收邮件（5 分钟内有效）`);
      } else {
        message.error(res.message || '发送验证码失败');
      }
    } catch (error: any) {
      message.error(error?.response?.data?.message || error?.message || '发送验证码失败，请重试');
    } finally {
      setSendingCode(false);
    }
  };

  const handleSubmit = async (values: LoginFormValues) => {
    try {
      const res =
        type === 'email'
          ? await userLoginByEmailUsingPOST({
              email: values.email,
              code: values.code,
            })
          : await userLoginUsingPOST({
              userAccount: values.userAccount,
              userPassword: values.userPassword,
            });
      if (res.code === 0) {
        message.success('登录成功！');
        await fetchUserInfo();
        const urlParams = new URL(window.location.href).searchParams;
        history.push(urlParams.get('redirect') || '/');
        return;
      }
      message.error(res.message || '登录失败，请重试！');
    } catch (error: any) {
      message.error(error?.response?.data?.message || error?.message || '登录失败，请重试！');
    }
  };

  return (
    <div className={containerClassName}>
      <Helmet>
        <title>
          {'登录'} - {Settings.title}
        </title>
      </Helmet>
      <div
        style={{
          flex: '1',
          padding: '32px 0',
        }}
      >
        <LoginForm<LoginFormValues>
          form={form}
          contentStyle={{
            minWidth: 280,
            maxWidth: '75vw',
          }}
          logo={<img alt="logo" src="/logo.svg" />}
          title="Epiph-BI"
          subTitle="Epiphany119 维护的智能数据分析平台"
          onFinish={handleSubmit}
        >
          <Tabs
            activeKey={type}
            onChange={setType}
            centered
            items={[
              {
                key: 'account',
                label: '账户密码登录',
              },
              {
                key: 'email',
                label: '邮箱验证码登录',
              },
            ]}
          />
          {type === 'account' && (
            <>
              <ProFormText
                name="userAccount"
                fieldProps={{
                  size: 'large',
                  prefix: <UserOutlined />,
                }}
                placeholder={'请输入用户名'}
                rules={[
                  {
                    required: true,
                    message: '用户名是必填项！',
                  },
                ]}
              />
              <ProFormText.Password
                name="userPassword"
                fieldProps={{
                  size: 'large',
                  prefix: <LockOutlined />,
                }}
                placeholder={'请输入密码'}
                rules={[
                  {
                    required: true,
                    message: '密码是必填项！',
                  },
                ]}
              />
            </>
          )}
          {type === 'email' && (
            <>
              <ProFormText
                name="email"
                fieldProps={{
                  size: 'large',
                  prefix: <MailOutlined />,
                }}
                placeholder={'请输入已绑定的邮箱'}
                rules={[
                  { required: true, message: '请输入已绑定的邮箱！' },
                  { type: 'email', message: '请输入有效的邮箱地址！' },
                ]}
              />
              <Space.Compact style={{ width: '100%', marginBottom: 24 }}>
                <div style={{ flex: 1 }}>
                  <ProFormText
                    name="code"
                    noStyle
                    fieldProps={{
                      size: 'large',
                      prefix: <SafetyOutlined />,
                      maxLength: 6,
                    }}
                    placeholder={'请输入 6 位邮箱验证码'}
                    rules={[{ required: true, message: '请输入邮箱验证码！' }]}
                  />
                </div>
                <Button
                  size="large"
                  loading={sendingCode}
                  disabled={cooldown > 0}
                  onClick={handleSendEmailCode}
                >
                  {cooldown > 0 ? `${cooldown}s 后重试` : '发送验证码'}
                </Button>
              </Space.Compact>
            </>
          )}
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              marginBottom: 24,
            }}
          >
            <Link to="/user/register">注册</Link>
            <Link to="/user/reset-password">忘记密码？</Link>
          </div>
        </LoginForm>
      </div>
      <Footer />
    </div>
  );
};
export default Login;
