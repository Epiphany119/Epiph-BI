import Footer from '@/components/Footer';
import {
  requestPasswordResetCodeUsingPOST,
  resetPasswordUsingPOST,
} from '@/services/yubi/userController';
import { Link } from '@@/exports';
import { LockOutlined, MailOutlined, SafetyOutlined, UserOutlined } from '@ant-design/icons';
import { LoginForm, ProFormText } from '@ant-design/pro-components';
import { useEmotionCss } from '@ant-design/use-emotion-css';
import { Helmet, history } from '@umijs/max';
import { Button, Form, message, Space } from 'antd';
import React, { useEffect, useState } from 'react';
import Settings from '../../../../config/defaultSettings';

type ResetFormValues = API.UserResetPasswordRequest & {
  email?: string;
};

const ResetPassword: React.FC = () => {
  const [form] = Form.useForm<ResetFormValues>();
  const [sendingCode, setSendingCode] = useState(false);
  const [cooldown, setCooldown] = useState(0);

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

  const handleSendCode = async () => {
    const userAccount = (form.getFieldValue('userAccount') || '').trim();
    const email = (form.getFieldValue('email') || '').trim();
    if (userAccount.length < 4) {
      message.warning('请先输入至少 4 位用户账号');
      return;
    }
    if (!email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      message.warning('请输入该账号绑定的有效邮箱');
      return;
    }
    setSendingCode(true);
    try {
      const res = await requestPasswordResetCodeUsingPOST({ userAccount, email });
      if (res.code === 0) {
        setCooldown(60);
        if (typeof res.data === 'string' && res.data.startsWith('DEV_FALLBACK:')) {
          const fallbackCode = res.data.slice('DEV_FALLBACK:'.length);
          form.setFieldValue('resetCode', fallbackCode);
          message.info(
            `本机代理拦截了 SMTP 端口，已启用本地开发兜底验证码：${fallbackCode}（5 分钟内有效）`,
            6,
          );
        } else {
          message.success(
            `重置验证码已发送至 ${res.data || '绑定邮箱'}，请前往邮箱查收（5 分钟内有效）`,
          );
        }
      } else {
        message.error(res.message || '发送验证码失败');
      }
    } catch (error: any) {
      message.error(error?.response?.data?.message || error?.message || '发送验证码失败，请重试');
    } finally {
      setSendingCode(false);
    }
  };

  const handleSubmit = async (values: ResetFormValues) => {
    if (values.newPassword !== values.checkPassword) {
      message.error('两次输入的新密码不一致');
      return;
    }
    try {
      const res = await resetPasswordUsingPOST({
        userAccount: values.userAccount,
        resetCode: values.resetCode,
        newPassword: values.newPassword,
        checkPassword: values.checkPassword,
      });
      if (res.code === 0 && res.data) {
        message.success('密码重置成功，请使用新密码登录');
        history.push('/user/login');
        return;
      }
      message.error(res.message || '密码重置失败');
    } catch (error: any) {
      message.error(error?.response?.data?.message || error?.message || '密码重置失败，请重试');
    }
  };

  return (
    <div className={containerClassName}>
      <Helmet>
        <title>
          {'重置密码'} - {Settings.title}
        </title>
      </Helmet>
      <div style={{ flex: '1', padding: '32px 0' }}>
        <LoginForm<ResetFormValues>
          form={form}
          contentStyle={{
            minWidth: 280,
            maxWidth: '75vw',
          }}
          logo={<img alt="logo" src="/logo.svg" />}
          title="Epiph-BI"
          subTitle="忘记密码 · 邮箱验证码重置"
          submitter={{
            searchConfig: {
              submitText: '重置密码',
            },
          }}
          onFinish={handleSubmit}
        >
          <ProFormText
            name="userAccount"
            fieldProps={{
              size: 'large',
              prefix: <UserOutlined />,
            }}
            placeholder="请输入需要重置的账号（至少 4 位）"
            rules={[
              { required: true, message: '请输入用户账号' },
              { min: 4, message: '账号长度不能少于 4 位' },
            ]}
          />
          <ProFormText
            name="email"
            fieldProps={{
              size: 'large',
              prefix: <MailOutlined />,
            }}
            placeholder="请输入该账号绑定的邮箱"
            rules={[
              { required: true, message: '请输入绑定邮箱' },
              { type: 'email', message: '请输入有效的邮箱地址' },
            ]}
          />
          <Space.Compact style={{ width: '100%', marginBottom: 24 }}>
            <div style={{ flex: 1 }}>
              <ProFormText
                name="resetCode"
                noStyle
                fieldProps={{
                  size: 'large',
                  prefix: <SafetyOutlined />,
                  maxLength: 6,
                }}
                placeholder="请输入邮箱收到的 6 位验证码"
                rules={[{ required: true, message: '请输入邮箱验证码' }]}
              />
            </div>
            <Button
              size="large"
              loading={sendingCode}
              disabled={cooldown > 0}
              onClick={handleSendCode}
            >
              {cooldown > 0 ? `${cooldown}s 后重试` : '发送邮箱验证码'}
            </Button>
          </Space.Compact>
          <ProFormText.Password
            name="newPassword"
            fieldProps={{
              size: 'large',
              prefix: <LockOutlined />,
            }}
            placeholder="请输入新密码（至少 8 位）"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 8, message: '新密码长度不能少于 8 位' },
            ]}
          />
          <ProFormText.Password
            name="checkPassword"
            fieldProps={{
              size: 'large',
              prefix: <LockOutlined />,
            }}
            placeholder="请再次确认新密码"
            rules={[
              { required: true, message: '请确认新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('newPassword') === value) {
                    return Promise.resolve();
                  }
                  return Promise.reject(new Error('两次输入的新密码不一致'));
                },
              }),
            ]}
          />
          <div style={{ marginBottom: 24 }}>
            <Link to="/user/login">返回登录</Link>
          </div>
        </LoginForm>
      </div>
      <Footer />
    </div>
  );
};

export default ResetPassword;
